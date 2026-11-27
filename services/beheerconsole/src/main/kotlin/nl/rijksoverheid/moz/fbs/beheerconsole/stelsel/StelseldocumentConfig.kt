package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import java.util.Optional

/** Wat een afnemer vooraf vastlegt om het stelseldocument te kunnen verifiëren. */
@ConfigMapping(prefix = "beheerconsole.stelseldocument")
interface StelseldocumentConfig {

    /**
     * De root van de stelselbeheerder als PEM. Ontbreekt hij, dan is de keten niet te controleren
     * en meldt het overzicht `NIET_GETOETST` in plaats van te doen alsof het document klopt.
     */
    fun root(): Optional<String>

    /** De OIN van de stelselbeheerder: `iss` en het `serialNumber` van het ondertekencertificaat. */
    fun uitgever(): String

    /** De omgeving waarvoor deze console het document accepteert (`environment`). */
    fun omgeving(): String

    /** Buiten dev en test zonder root starten; alleen bewust, zie `StelselregisterAdres`. */
    @WithName("unsafe-zonder-root")
    @WithDefault("false")
    fun unsafeZonderRoot(): Boolean
}
