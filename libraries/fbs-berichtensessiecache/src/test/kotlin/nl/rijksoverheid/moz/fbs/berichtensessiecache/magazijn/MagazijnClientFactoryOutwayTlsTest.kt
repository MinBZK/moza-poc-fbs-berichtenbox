package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.mockk.mockk
import io.quarkus.tls.TlsConfiguration
import io.quarkus.tls.TlsConfigurationRegistry
import nl.rijksoverheid.moz.fbs.common.fsc.OutwayTls
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Wie krijgt het outway-anchor mee. Twee assen die onafhankelijk moeten werken: is er een
 * configuratie onder die naam, en loopt dít magazijn eigenlijk wel door de outway.
 *
 * De tweede as is de kern. Een magazijn zonder grant-hash wordt rechtstreeks aangeroepen en
 * presenteert een publiek certificaat; omdat een named TLS-configuratie de JVM-default
 * trust-store vervángt, zou het anchor die verbinding juist breken.
 */
class MagazijnClientFactoryOutwayTlsTest {

    private val configuratie = mockk<TlsConfiguration>()

    private fun factoryMet(registry: TlsConfigurationRegistry) =
        MagazijnClientFactory(
            register = mockk<Magazijnregister>(relaxed = true),
            connectTimeoutMs = 2000L,
            readTimeoutMs = 12000L,
            tlsRegistry = registry,
        )

    private fun inschrijving(grantHash: String?) = Magazijninschrijving(
        oin = Oin("00000001003214345000"),
        url = URI.create("https://magazijn.test"),
        naam = "Magazijn A",
        grantHash = grantHash,
    )

    @Test
    fun `een registry zonder configuratie onder die naam levert niets op`() {
        val factory = factoryMet(testTlsRegistry())

        assertNull(factory.outwayTlsConfiguratie(inschrijving(grantHash = "abc123")))
    }

    @Test
    fun `een andere naam in de registry telt niet mee`() {
        val factory = factoryMet(testTlsRegistry("iets-anders" to configuratie))

        assertNull(factory.outwayTlsConfiguratie(inschrijving(grantHash = "abc123")))
    }

    @Test
    fun `een magazijn met grant-hash krijgt de configuratie onder de outway-naam`() {
        val factory = factoryMet(testTlsRegistry(OutwayTls.CONFIG_NAAM to configuratie))

        assertSame(configuratie, factory.outwayTlsConfiguratie(inschrijving(grantHash = "abc123")))
    }

    @Test
    fun `een magazijn zonder grant-hash krijgt het anchor niet, ook al is het geconfigureerd`() {
        val factory = factoryMet(testTlsRegistry(OutwayTls.CONFIG_NAAM to configuratie))

        assertNull(factory.outwayTlsConfiguratie(inschrijving(grantHash = null)))
    }

    /**
     * De naam is een contract met omgevingsvariabelen buiten de codebase
     * (`QUARKUS_TLS_OUTWAY_TRUST_STORE_PEM_CERTS`,
     * `QUARKUS_REST_CLIENT_PROFIEL_SERVICE_TLS_CONFIGURATION_NAME=outway`). Alle andere tests
     * verwijzen naar de constante en zouden een hernoeming dus niet merken, terwijl elke
     * gedeployde omgeving stilvalt.
     */
    @Test
    fun `de configuratienaam ligt vast`() {
        assertEquals("outway", OutwayTls.CONFIG_NAAM)
    }
}
