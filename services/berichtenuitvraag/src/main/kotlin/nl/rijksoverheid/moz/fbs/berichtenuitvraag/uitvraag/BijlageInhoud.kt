package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Een bijlage uit het magazijn, klaar om door te geven. Het begin is al gelezen; de rest staat
 * nog in de open magazijn-response en gaat pas bij [schrijfNaar] door, zonder eerst in de heap
 * te belanden. Is de bijlage kleiner dan [BEGIN_BYTES], dan is er geen rest en is de
 * magazijn-response al dicht.
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

    /**
     * Schrijft de bijlage naar [doel] en sluit daarna de magazijn-response. Een leesfout in de
     * rest komt als [BijlageOverdrachtAfgebroken]; een schrijffout, omdat de afnemer weg is,
     * komt ongewijzigd door. Schrijft niets als [sluitAlsOngeschreven] al was aangeroepen: dan
     * is het verzoek al voorbij.
     */
    fun schrijfNaar(doel: OutputStream) {
        if (!toestand.compareAndSet(Toestand.ONGESCHREVEN, Toestand.BEZIG)) return

        try {
            doel.write(begin)

            val bron = rest ?: return

            // Het begin gaat de deur uit vóór het eerste stuk van de rest. Daarmee is het antwoord
            // begonnen, en wordt een leesfout hierna een verbroken connection in plaats van een
            // foutantwoord onder de headers van de bijlage.
            doel.flush()
            kopieer(bron, doel)
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

    private fun kopieer(bron: InputStream, doel: OutputStream) {
        val buffer = ByteArray(KOPIEER_BYTES)

        while (true) {
            val gelezen = try {
                bron.read(buffer)
            } catch (e: IOException) {
                throw BijlageOverdrachtAfgebroken(e)
            }

            if (gelezen < 0) return

            doel.write(buffer, 0, gelezen)
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
         * afgekapte stream geeft dan een 502, en de magazijn-connection is meteen weer vrij. Alleen
         * een grotere bijlage houdt een worker-thread en een connection vast zolang de afnemer leest.
         */
        const val BEGIN_BYTES = 1024 * 1024

        private const val KOPIEER_BYTES = 16 * 1024
    }
}

/** Het magazijn brak de overdracht af nadat het antwoord aan de afnemer al begonnen was. */
class BijlageOverdrachtAfgebroken(oorzaak: IOException) : IOException("overdracht vanuit het magazijn afgebroken", oorzaak)

/** Schrijven naar de afnemer mislukte; meestal omdat die de download afbrak. */
class BijlageNietAfgeleverd(oorzaak: IOException) : IOException("bijlage niet volledig bij de afnemer afgeleverd", oorzaak)

/**
 * Dit antwoord bereikt niemand. Beide excepties ontstaan pas nadat [BijlageInhoud.schrijfNaar] het
 * begin geschreven heeft, en een leesfout in de rest pas na de flush daarvan; het antwoord is dan
 * al begonnen, of de afnemer is weg, en Quarkus reset de connection. Zonder deze mapper kwam de
 * fout, die de resource al logde, een tweede keer als "onverwachte exception" op error in de log.
 * Een globale mapper en geen in de resource: die van de resource geldt niet voor wat er na de
 * methode, bij het schrijven, misgaat.
 */
class BijlageOverdrachtGestoptMapper {
    @ServerExceptionMapper(BijlageOverdrachtAfgebroken::class, BijlageNietAfgeleverd::class)
    fun gestopt(): Response = Response.status(Response.Status.BAD_GATEWAY).build()
}
