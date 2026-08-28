package nl.rijksoverheid.moz.fbs.democonsole.storing

import java.util.Optional

/**
 * Configuratie-dubbel voor de storing-tests.
 *
 * `null` is "niet gezet" (`Optional.empty`), een lege string is een env-var die expliciet leeg
 * gezet is. Smallrye-config behandelt die twee verschillend en de productiecode moet ze juist
 * gelijktrekken, dus beide gevallen zijn hier uit te drukken.
 */
internal data class TestInstantie(
    val url: String? = null,
    val listen: String? = null,
    val upstream: String? = null,
) : ToxiproxyConfig.Instantie {

    override fun url(): Optional<String> = Optional.ofNullable(url)

    override fun listen(): Optional<String> = Optional.ofNullable(listen)

    override fun upstream(): Optional<String> = Optional.ofNullable(upstream)
}

internal fun testConfig(vararg proxies: Pair<String, TestInstantie>) = object : ToxiproxyConfig {

    override fun toxiproxy(): Map<String, ToxiproxyConfig.Instantie> = proxies.toMap()
}
