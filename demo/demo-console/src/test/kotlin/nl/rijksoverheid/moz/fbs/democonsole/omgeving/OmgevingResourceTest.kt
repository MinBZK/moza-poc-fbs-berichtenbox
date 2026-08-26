package nl.rijksoverheid.moz.fbs.democonsole.omgeving

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.democonsole.storing.ToxiproxyRegister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional

class OmgevingResourceTest {

    private fun resource(basis: String?, vararg proxies: String): OmgevingResource {
        val config = mockk<OmgevingConfig> { every { uitvraagBasis() } returns Optional.ofNullable(basis) }
        val register = mockk<ToxiproxyRegister> { every { namen() } returns proxies.toSet() }

        return OmgevingResource(config, register)
    }

    @Test
    fun `zonder geconfigureerde basis blijft het veld leeg zodat de pagina terugvalt`() {
        // Lokaal is er geen vaste basis: de pagina leidt hem dan af uit de browser-locatie, wat
        // ook op een VM- of containeradres werkt. Een verzonnen default zou dat breken.
        assertEquals("", resource(null).omgeving().uitvraagBasis)
    }

    @Test
    fun `een geconfigureerde basis komt ongewijzigd door`() {
        val basis = "https://uitvraag-demo-mpfb-8wh.example/api/v1"

        assertEquals(basis, resource(basis).omgeving().uitvraagBasis)
    }

    @Test
    fun `storingen spiegelt het register, gesorteerd`() {
        assertEquals(
            listOf("aanmeld", "profiel", "redis"),
            resource(null, "redis", "profiel", "aanmeld").omgeving().storingen,
        )
    }

    @Test
    fun `een omgeving zonder storingen levert een lege lijst en geen fout`() {
        assertEquals(emptyList<String>(), resource(null).omgeving().storingen)
    }
}
