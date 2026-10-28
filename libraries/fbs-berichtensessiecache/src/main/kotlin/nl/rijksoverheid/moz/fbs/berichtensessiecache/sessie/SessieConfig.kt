package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

/**
 * Hoe lang de gegevens van een ontvanger hoogstens blijven staan als niemand ze actief wist. Een
 * sessie verloopt niet uit zichzelf: ze loopt tot het uitloggen. Wat daarbuiten blijft staan, zoals
 * de gegevens van een sessie die nooit uitgelogd wordt, wist Redis na [maxDuur] plus [vangnetMarge]
 * (zie [dataTtl]). Elke consument roept bij het opstarten [valideer] aan, zodat een ongeldige waarde
 * de pod tegenhoudt met een melding over die waarde.
 */
@ConfigMapping(prefix = "berichtensessiecache.sessie")
internal interface SessieConfig {

    @WithDefault("PT12H")
    fun maxDuur(): Duration

    /** Hoe ver de Redis-TTL van de gegevens voorbij [maxDuur] ligt; alleen een vangnet als actief opruimen uitblijft. */
    @WithDefault("PT1H")
    fun vangnetMarge(): Duration
}

/**
 * Weigert een duur van nul of minder, met de configuratiesleutel in de melding. Hier en niet
 * in één consument: elke bean die de configuratie gebruikt, roept dit aan, zodat de pod met een
 * melding over de waarde zelf stopt, welke bean ook het eerst start. Een duur van nul laat Redis
 * elke sessie en alle gegevens direct wissen.
 */
internal fun SessieConfig.valideer() {
    require(maxDuur().isPositive) {
        "berichtensessiecache.sessie.max-duur (${maxDuur()}) moet groter zijn dan 0"
    }

    require(vangnetMarge().isPositive) {
        "berichtensessiecache.sessie.vangnet-marge (${vangnetMarge()}) moet groter zijn dan 0"
    }
}

/**
 * De Redis-TTL op de sessie-administratie en de berichtgegevens van een ontvanger, gezet bij elke
 * start en elke schrijfactie. Wissen bij de laatste uitlog is een actieve stap; deze TTL ruimt op wat
 * niemand uitlogt of wat een mislukt opruimen liet staan, en dat zonder logregel.
 */
internal val SessieConfig.dataTtl: Duration get() = maxDuur().plus(vangnetMarge())
