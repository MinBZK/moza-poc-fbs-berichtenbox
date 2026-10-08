package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration
import java.util.Optional

@ConfigMapping(prefix = "stelseldocument")
interface StelseldocumentConfig {

    /** OIN van de stelselbeheerder; komt als `iss` in het document. */
    fun uitgeverOin(): String

    /** Komt als `environment` in het document, zodat een demo-exemplaar herkenbaar is. */
    fun omgeving(): String

    /**
     * Hoe lang een uitgegeven document geldt. Moet ruim boven het ververs-interval liggen:
     * een afnemer die een exemplaar vlak vóór de volgende uitgifte ophaalt, moet het nog
     * kunnen gebruiken.
     */
    @WithDefault("PT24H")
    fun geldigheid(): Duration

    /**
     * Interval waarop een nieuw exemplaar wordt uitgegeven. Staat in de mapping omdat SmallRye
     * een onbekende sleutel onder deze prefix weigert; de planner leest dezelfde sleutel.
     */
    @WithDefault("PT1H")
    fun verversen(): Duration

    fun keystore(): Keystore

    interface Keystore {

        /** Pad naar de PKCS#12-keystore. Verplicht buiten ontwikkel- en testmodus. */
        fun pad(): Optional<String>

        fun wachtwoord(): Optional<String>

        @WithDefault("stelseldocument")
        fun alias(): String
    }
}
