package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

/**
 * Levensduur van een inlogsessie. Een sessie verloopt na [inactiviteit] zonder verzoek, en hoe dan
 * ook na [maxDuur] vanaf de start — wat het eerst komt. [RedisSessieRegister] weigert bij het
 * opstarten een waarde die nul of negatief is.
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
