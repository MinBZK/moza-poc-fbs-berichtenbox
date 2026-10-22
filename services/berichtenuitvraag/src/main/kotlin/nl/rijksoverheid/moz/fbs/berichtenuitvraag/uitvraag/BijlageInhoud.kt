package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Een bijlage uit het magazijn, klaar om door te geven. Het begin is al gelezen. Is de bijlage
 * kleiner dan [BEGIN_BYTES], dan is dat de hele bijlage ([volledig]) en is de magazijn-response al
 * dicht. Anders staat de rest nog in de open magazijn-response en gaat die pas bij [schrijfNaar]
 * door, zonder eerst in de heap te belanden.
 *
 * Bewust geen `data class`: er wordt nergens vergeleken of gedestructureerd, en de
 * gegenereerde `toString` zou de bestandsnaam — die persoonsgegevens kan bevatten — in elke
 * logregel zetten waarin het object per ongeluk belandt.
 */
class BijlageInhoud internal constructor(
    val mimeType: String,
    val bestandsnaam: String?,
    /** Het aantal bytes, als dat vaststaat; anders gaat het antwoord chunked. */
    val lengte: Long?,
    private val begin: ByteArray,
    private val rest: InputStream?,
    private val magazijnResponse: Response?,
) {

    private val toestand = AtomicReference(Toestand.ONGESCHREVEN)

    /** Sinds wanneer de write naar de afnemer loopt die nu wacht ([System.nanoTime]); `null` als er geen loopt. */
    @Volatile
    private var schrijftSinds: Long? = null

    /** De hele bijlage, als die in het begin paste; dan valt er niets te streamen. */
    val volledig: ByteArray? get() = begin.takeIf { rest == null }

    /**
     * Schrijft de bijlage naar [doel] en sluit daarna de magazijn-response.
     * - Een leesfout in de rest komt als [BijlageOverdrachtAfgebroken], behalve een interrupt: die
     *   komt ongewijzigd door, met de interrupt-vlag weer gezet.
     * - Een schrijffout, meestal omdat de afnemer weg is, komt ongewijzigd door.
     * - Meldt [afnemerWeg] dat de afnemer weg is, of was het verzoek al voorbij voordat het schrijven
     *   begon, dan volgt [BijlageNietAfgeleverd]. Een write naar een gesloten connection mislukt niet
     *   altijd; zonder deze controle zou de rest stil uit het magazijn worden leeggelezen.
     */
    fun schrijfNaar(doel: OutputStream, afnemerWeg: () -> Boolean) {
        if (!toestand.compareAndSet(Toestand.ONGESCHREVEN, Toestand.BEZIG)) {
            throw BijlageNietAfgeleverd(IOException("het verzoek was al voorbij"))
        }

        try {
            schrijf { doel.write(begin) }

            val bron = rest ?: return

            // Het begin gaat de deur uit vóór het eerste stuk van de rest. Daarmee is het antwoord
            // begonnen, en wordt een leesfout hierna een verbroken connection in plaats van een
            // foutantwoord onder de headers van de bijlage.
            schrijf { doel.flush() }
            kopieer(bron, doel, afnemerWeg)
        } finally {
            toestand.set(Toestand.GESLOTEN)
            sluitMagazijnResponse()
        }
    }

    /**
     * Sluit de magazijn-response als [schrijfNaar] nooit begon: bij een HEAD-verzoek, een filter
     * dat gooit of een afnemer die al weg is. Doet niets tijdens of na het schrijven: dan sluit
     * [schrijfNaar] zelf, op de thread die de stream leest.
     */
    fun sluitAlsOngeschreven() {
        if (toestand.compareAndSet(Toestand.ONGESCHREVEN, Toestand.GESLOTEN)) sluitMagazijnResponse()
    }

    /** Of de write die nu loopt al langer dan [grens] op de afnemer wacht. */
    fun wachtLangerDan(grens: Duration): Boolean {
        val sinds = schrijftSinds ?: return false

        return System.nanoTime() - sinds > grens.toNanos()
    }

    private fun kopieer(bron: InputStream, doel: OutputStream, afnemerWeg: () -> Boolean) {
        val buffer = ByteArray(KOPIEER_BYTES)

        while (true) {
            if (afnemerWeg()) throw BijlageNietAfgeleverd(IOException("de afnemer is weg"))

            val gelezen = try {
                bron.read(buffer)
            } catch (e: InterruptedIOException) {
                Thread.currentThread().interrupt()

                throw e
            } catch (e: IOException) {
                throw BijlageOverdrachtAfgebroken(e)
            }

            if (gelezen < 0) return

            schrijf { doel.write(buffer, 0, gelezen) }
        }
    }

    private inline fun schrijf(write: () -> Unit) {
        schrijftSinds = System.nanoTime()

        try {
            write()
        } finally {
            schrijftSinds = null
        }
    }

    private fun sluitMagazijnResponse() {
        // Een falende close mag de echte fout niet maskeren, maar op warn: een aanhoudend faaltje
        // hier betekent een lekkende connection pool en moet in prod zichtbaar zijn.
        runCatching { magazijnResponse?.close() }
            .onFailure { log.warnf(it, "kon upstream-response niet sluiten na magazijn-bijlage-read (mogelijk connectie-pool-lek)") }
    }

    private enum class Toestand { ONGESCHREVEN, BEZIG, GESLOTEN }

    internal companion object {
        private val log: Logger = Logger.getLogger(BijlageInhoud::class.java)

        /**
         * Zoveel leest de uitvraag in voordat ze gaat streamen. Een kleinere bijlage, en dat zijn
         * de bijlagen die in de praktijk voorkomen, wordt binnen de methode helemaal gelezen: een
         * afgekapte stream geeft dan een 502, de magazijn-connection is meteen weer vrij, en het
         * antwoord gaat de deur uit zonder een worker-thread vast te houden. Alleen een grotere
         * bijlage houdt een worker-thread en een connection vast zolang de afnemer leest.
         */
        const val BEGIN_BYTES = 1024 * 1024

        private const val KOPIEER_BYTES = 16 * 1024
    }
}

/** Het magazijn brak de overdracht af nadat het antwoord aan de afnemer al begonnen was. */
class BijlageOverdrachtAfgebroken(oorzaak: IOException) : IOException("overdracht vanuit het magazijn afgebroken", oorzaak)

/** De bijlage kwam niet volledig bij de afnemer; meestal omdat die de download afbrak. */
class BijlageNietAfgeleverd(oorzaak: IOException) : IOException("bijlage niet volledig bij de afnemer afgeleverd", oorzaak)

/**
 * Dit antwoord bereikt niemand. [BijlageDoorgifte] reset het antwoord zelf voordat ze een van deze
 * excepties doorgooit, en de StreamingOutput-writer van Quarkus doet dat ook zodra de headers al weg
 * zijn, vóór deze mapper draait. Vandaar geen problem-body: die zou alleen nog naar een gereset of
 * gesloten antwoord gaan.
 *
 * Zonder deze mapper komt de fout, die [BijlageDoorgifte] al logde, een tweede keer als "onverwachte
 * exception" op error in de log. Een globale mapper en geen in de resource: die van de resource
 * geldt niet voor wat er na de methode, bij het schrijven, misgaat.
 */
class BijlageOverdrachtGestoptMapper {
    @ServerExceptionMapper(BijlageOverdrachtAfgebroken::class, BijlageNietAfgeleverd::class)
    fun gestopt(): Response = Response.status(Response.Status.BAD_GATEWAY).build()
}
