package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.quarkus.tls.TlsConfiguration
import io.quarkus.tls.TlsConfigurationRegistry
import java.util.Optional

/**
 * Een `TlsConfigurationRegistry` zonder Quarkus eromheen, zodat tests die de factory buiten CDI
 * bouwen niet alsnog een container nodig hebben. Leeg meegeven is het normale geval: dat is de
 * omgeving waarin geen outway-anchor geconfigureerd is.
 */
internal fun testTlsRegistry(vararg configuraties: Pair<String, TlsConfiguration>): TlsConfigurationRegistry {
    val perNaam = configuraties.toMap()

    return object : TlsConfigurationRegistry {
        override fun get(naam: String): Optional<TlsConfiguration> = Optional.ofNullable(perNaam[naam])

        override fun getDefault(): Optional<TlsConfiguration> = Optional.empty()

        override fun register(naam: String, configuratie: TlsConfiguration) =
            throw UnsupportedOperationException("test-registry is read-only")
    }
}
