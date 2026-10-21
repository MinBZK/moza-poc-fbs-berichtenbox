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
