package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.mockk.mockk
import io.quarkus.tls.TlsConfiguration
import io.quarkus.tls.TlsConfigurationRegistry
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import nl.rijksoverheid.moz.fbs.magazijnregister.OutwayTls
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * De keuze "wel of geen eigen trust-anker voor de outway". Zonder configuratie moet het
 * verkeer op de JVM-default trust-store blijven leunen — dat is het gedrag van elke omgeving
 * die de outway via een publiek vertrouwde ingress bereikt, en dat mag deze knop niet stiekem
 * omzetten.
 */
class MagazijnClientFactoryOutwayTlsTest {

    private val configuratie = mockk<TlsConfiguration>()

    private fun factoryMet(registry: TlsConfigurationRegistry?) =
        MagazijnClientFactory(
            register = mockk<Magazijnregister>(relaxed = true),
            connectTimeoutMs = 2000L,
            readTimeoutMs = 12000L,
            tlsRegistry = registry,
        )

    @Test
    fun `zonder registry blijft het bij de JVM-default trust-store`() {
        assertNull(factoryMet(null).outwayTlsConfiguratie())
    }

    @Test
    fun `een registry zonder configuratie onder die naam levert niets op`() {
        val registry = registryMet(emptyMap())

        assertNull(factoryMet(registry).outwayTlsConfiguratie())
    }

    @Test
    fun `een andere naam in de registry telt niet mee`() {
        val registry = registryMet(mapOf("iets-anders" to configuratie))

        assertNull(factoryMet(registry).outwayTlsConfiguratie())
    }

    @Test
    fun `de configuratie onder de outway-naam wordt gebruikt`() {
        val registry = registryMet(mapOf(OutwayTls.CONFIG_NAAM to configuratie))

        assertSame(configuratie, factoryMet(registry).outwayTlsConfiguratie())
    }

    private fun registryMet(configuraties: Map<String, TlsConfiguration>) =
        object : TlsConfigurationRegistry {
            override fun get(naam: String): Optional<TlsConfiguration> =
                Optional.ofNullable(configuraties[naam])

            override fun getDefault(): Optional<TlsConfiguration> = Optional.empty()

            override fun register(naam: String, configuratie: TlsConfiguration) =
                throw UnsupportedOperationException("test-registry is read-only")
        }
}
