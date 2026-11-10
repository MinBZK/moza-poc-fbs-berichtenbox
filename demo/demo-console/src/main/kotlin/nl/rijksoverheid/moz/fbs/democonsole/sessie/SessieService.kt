package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.quarkus.redis.datasource.RedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import java.util.logging.Level
import java.util.logging.Logger

/**
 * `gewisteKeys` heeft twee lezers: `bediening.js` toont het aantal, en `demo/meet-fanout.sh` eist het
 * als geheel getal voordat een ronde mag beginnen — een inlogmuur kan met 200 antwoorden, maar niet
 * met dit veld. Hernoem of hertype je het, pas dan beide mee aan.
 */
data class VerlopenSessiecache(val gewisteKeys: Int, val beeindigdeSessies: Int)

/**
 * Laat de sessiecache "verlopen" door alle keys onder `berichtensessiecache:` en alle berichthashes
 * te wissen: de berichtgegevens en de administratie van de inlogsessies. De hashes staan buiten dat
 * prefix en blijven anders achter als gegevens zonder sessie. Zonder die keys kent de uitvraag het
 * sessie-id niet meer en geeft elke `/berichten`-aanroep 401; de berichtenbox start dan een nieuwe
 * sessie en een nieuwe ophaalronde. Bewust alleen DEL op de twee prefixen; nooit
 * FLUSHDB/FT.DROPINDEX (die slopen de index).
 */
@ApplicationScoped
class SessieService(private val redis: RedisDataSource, private val beheer: SessieBeheer) {

    private val log = Logger.getLogger(SessieService::class.java.name)

    /**
     * Telt eerst de lopende sessies: het aantal keys zegt daar niets over, want alle sessies van een
     * ontvanger zijn leden van één sleutel. Na het wissen is er niets meer te tellen.
     */
    fun laatSessiesVerlopen(): VerlopenSessiecache {
        val lopend = beheer.telLopende()

        return VerlopenSessiecache(gewisteKeys = wis(), beeindigdeSessies = lopend)
    }

    private fun wis(): Int {
        val keys = PATRONEN.flatMap { redis.key().keys(it) }

        return if (keys.isEmpty()) 0 else redis.key().del(*keys.toTypedArray())
    }

    /**
     * Voor knoppen die de magazijnen leeggooien: zonder dit tonen open berichtenboxen de berichten
     * van vóór die knop, en vullen de aanmeldingen van een nieuwe basisvulling die oude sessies
     * aan. Mag de knop zelf niet laten mislukken — het legen is dan al gebeurd — dus `null` bij
     * een fout in plaats van een exception.
     */
    fun laatSessiesVerlopenZoMogelijk(): Int? =
        runCatching { wis() }.getOrElse { fout ->
            log.log(Level.WARNING, "sessiecache niet gewist", fout)

            null
        }

    private companion object {

        // Versieloos patroon: de sessiecache versienummert zijn sleutels (`:v1:`, `:v2:`, …) en
        // die versie bumpt bij elke wijziging van het opslagformaat. Een patroon met een vast
        // versienummer zou na zo'n bump niets meer vinden en tóch "0 keys gewist" melden —
        // een knop die succes rapporteert zonder iets te doen. De prefix zelf is specifiek genoeg.
        const val SESSIE_PATROON = "berichtensessiecache:*"

        // De berichthashes (`bericht:v4:<magazijnId>:<berichtId>`) staan buiten het prefix van de
        // sessiecache; met dat patroon alleen blijft de RediSearch-index gevuld met berichten van gewiste sessies.
        // Versieloos om dezelfde reden als hierboven.
        const val BERICHT_PATROON = "bericht:*"

        val PATRONEN = listOf(SESSIE_PATROON, BERICHT_PATROON)
    }
}
