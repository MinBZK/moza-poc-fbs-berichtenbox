package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

/**
 * Hoe lang de sessies van een ontvanger hoogstens blijven staan als niemand ze beëindigt. Een
 * sessie verloopt niet uit zichzelf: ze loopt tot het uitloggen. Wordt ze nooit beëindigd, dan wist
 * Redis de sessie-administratie van de ontvanger [maxDuur] na zijn laatste start. Elke consument
 * roept bij het opstarten [valideer] aan, zodat een ongeldige waarde de pod tegenhoudt met een
 * melding over die waarde.
 */
@ConfigMapping(prefix = "berichtensessiecache.sessie")
internal interface SessieConfig {

    @WithDefault("PT12H")
    fun maxDuur(): Duration
}

/**
 * Weigert een duur van nul of minder, met de configuratiesleutel in de melding. Hier en niet
 * in één consument: elke bean die de configuratie gebruikt, roept dit aan, zodat de pod met een
 * melding over de waarde zelf stopt, welke bean ook het eerst start. Een duur van nul laat Redis
 * elke sessie direct wissen.
 */
internal fun SessieConfig.valideer() {
    require(maxDuur().isPositive) {
        "berichtensessiecache.sessie.max-duur (${maxDuur()}) moet groter zijn dan 0"
    }
}

