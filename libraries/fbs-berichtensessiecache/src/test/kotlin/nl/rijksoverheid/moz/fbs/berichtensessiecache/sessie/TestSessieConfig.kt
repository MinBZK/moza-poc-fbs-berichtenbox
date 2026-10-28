package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import java.time.Duration

/** [SessieConfig] buiten Quarkus, met de productie-defaults tenzij een test iets anders zet. */
internal data class TestSessieConfig(
    val inactiviteit: Duration = Duration.ofMinutes(15),
    val maxDuur: Duration = Duration.ofHours(12),
    val vangnetMarge: Duration = Duration.ofHours(1),
    val veegInterval: Duration = Duration.ofSeconds(30),
) : SessieConfig {
    override fun inactiviteit() = inactiviteit
    override fun maxDuur() = maxDuur
    override fun vangnetMarge() = vangnetMarge
    override fun veegInterval() = veegInterval
}
