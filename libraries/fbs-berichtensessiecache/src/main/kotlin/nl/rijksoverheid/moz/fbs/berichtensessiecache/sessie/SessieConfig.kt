package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

/**
 * Levensduur van een inlogsessie. Een sessie verloopt na [inactiviteit] zonder verzoek, en hoe dan
 * ook na [maxDuur] vanaf de start — wat het eerst komt; een [inactiviteit] boven [maxDuur] is dus
 * toegestaan, maar maakt [maxDuur] de enige grens. Elke consument roept bij het opstarten
 * [valideer] aan, zodat een ongeldige waarde de pod tegenhoudt met een melding over die waarde.
 */
@ConfigMapping(prefix = "berichtensessiecache.sessie")
internal interface SessieConfig {

    @WithDefault("PT15M")
    fun inactiviteit(): Duration

    @WithDefault("PT12H")
    fun maxDuur(): Duration

    /** Hoe ver de Redis-TTL van de gegevens voorbij [maxDuur] ligt; alleen een vangnet als actief opruimen faalt. */
    @WithDefault("PT1H")
    fun vangnetMarge(): Duration

    /** Lopende sessies per ontvanger; daarboven weigert een start in plaats van een bestaande sessie te verdringen. */
    @WithDefault("5")
    fun maxPerOntvanger(): Int

    /** Hoe vaak verlopen sessies actief worden opgezocht en opgeruimd. */
    @WithDefault("PT30S")
    fun veegInterval(): Duration
}

/**
 * Weigert een duur of grens van nul of minder, met de configuratiesleutel in de melding. Hier en niet
 * in één consument: elke bean die de configuratie gebruikt, roept dit aan, zodat de pod met een
 * melding over de waarde zelf stopt, welke bean ook het eerst start. Een duur van nul laat elke
 * sessie direct verlopen, of de veegtaak onafgebroken draaien; een grens van nul weigert elke inlog.
 */
internal fun SessieConfig.valideer() {
    require(inactiviteit().isPositive) {
        "berichtensessiecache.sessie.inactiviteit (${inactiviteit()}) moet groter zijn dan 0"
    }

    require(maxDuur().isPositive) {
        "berichtensessiecache.sessie.max-duur (${maxDuur()}) moet groter zijn dan 0"
    }

    require(vangnetMarge().isPositive) {
        "berichtensessiecache.sessie.vangnet-marge (${vangnetMarge()}) moet groter zijn dan 0"
    }

    require(maxPerOntvanger() > 0) {
        "berichtensessiecache.sessie.max-per-ontvanger (${maxPerOntvanger()}) moet groter zijn dan 0"
    }

    require(veegInterval().isPositive) {
        "berichtensessiecache.sessie.veeg-interval (${veegInterval()}) moet groter zijn dan 0"
    }
}

/**
 * De Redis-TTL op de berichtgegevens van een ontvanger, gezet bij elke schrijfactie. Geen sessie
 * leeft langer dan start + [SessieConfig.maxDuur], dus wat nu geschreven wordt, overleeft elke
 * sessie die er nu loopt. Wissen is een actieve stap; deze TTL vangt alleen een mislukt opruimen op.
 */
internal val SessieConfig.dataTtl: Duration get() = maxDuur().plus(vangnetMarge())

/**
 * Hoe ver een ontvanger wiens opruimen mislukte in de verloopindex naar achteren schuift. Zonder
 * uitstel blijft hij vooraan staan en krijgt elke pod bij elke ronde dezelfde mislukkers, terwijl
 * gezonde ontvangers erachter wachten tot de Redis-TTL. Minstens één interval, zodat de ronde die
 * hem net zag hem niet opnieuw probeert; minstens 30 s, zodat een kort interval een aanhoudende
 * fout niet in een stroom foutregels verandert.
 */
internal val SessieConfig.veegUitstel: Duration get() = maxOf(veegInterval(), MIN_VEEG_UITSTEL)

private val MIN_VEEG_UITSTEL: Duration = Duration.ofSeconds(30)
