package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import java.time.Duration

/** [SessieConfig] buiten Quarkus, met de productie-defaults tenzij een test iets anders zet. */
internal data class TestSessieConfig(
    val maxDuur: Duration = Duration.ofHours(12),
    val vangnetMarge: Duration = Duration.ofHours(1),
) : SessieConfig {
    override fun maxDuur() = maxDuur
    override fun vangnetMarge() = vangnetMarge
}
