package nl.rijksoverheid.moz.fbs.democonsole.omgeving

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.democonsole.storing.ToxiproxyRegister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional

class OmgevingResourceTest {

    private fun resource(
        basis: String?,
        vararg proxies: String,
        simulator: Boolean = true,
        sessiecache: Boolean = true,
        berichtenbox: String? = null,
    ): OmgevingResource {
        val config = mockk<OmgevingConfig> {
            every { uitvraagBasis() } returns Optional.ofNullable(basis)
            every { sessiecache() } returns sessiecache
            every { simulator() } returns simulator
            every { berichtenboxUrl() } returns Optional.ofNullable(berichtenbox)
        }
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

    @Test
    fun `een omgeving met precies één storing levert een lijst met dat ene element`() {
        // Onderscheidt "geeft het enige element terug" van "discrimineert per naam" — een lijst
        // van meerdere elementen dekt dat verschil niet.
        assertEquals(listOf("redis"), resource(null, "redis").omgeving().storingen)
    }

    @Test
    fun `een ingerichte simulator komt als true door`() {
        // Het paneel moet vooraf weten of deze omgeving gesimuleerde magazijnen kent, anders leest
        // een mislukte uitlezing als "niet ingericht".
        assertEquals(true, resource(null).omgeving().simulator)
    }

    @Test
    fun `een omgeving zonder simulator meldt false zodat de pagina die knoppen weglaat`() {
        assertEquals(false, resource(null, simulator = false).omgeving().simulator)
    }

    @Test
    fun `een bereikbare sessiecache komt als true door`() {
        assertEquals(true, resource(null).omgeving().sessiecache)
    }

    @Test
    fun `een onbereikbare sessiecache komt als false door zodat de pagina de knop weglaat`() {
        // De sessiecache staat in een ander project dan de console. Waar het verkeer daarheen niet
        // openstaat, geeft de knop gegarandeerd een fout; hem tonen kost tijdens een demo uitleg
        // die niets toevoegt.
        assertEquals(false, resource(null, sessiecache = false).omgeving().sessiecache)
    }

    @Test
    fun `zonder geconfigureerde berichtenbox blijft het veld leeg zodat het paneel het eigen pad probeert`() {
        // Lokaal zet de demo-proxy de berichtenbox op dezelfde origin; daar is een adres uit de
        // configuratie niet alleen overbodig maar ook fout zodra iemand de stack op een ander
        // adres opent.
        assertEquals("", resource(null).omgeving().berichtenboxUrl)
    }

    @Test
    fun `een geconfigureerde berichtenbox komt ongewijzigd door`() {
        val url = "https://proeftuin-demo-mpfm-w3h.example/moza/berichtenbox/"

        assertEquals(url, resource(null, berichtenbox = url).omgeving().berichtenboxUrl)
    }
}
