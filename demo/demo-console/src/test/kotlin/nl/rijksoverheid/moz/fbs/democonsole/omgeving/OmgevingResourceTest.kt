package nl.rijksoverheid.moz.fbs.democonsole.omgeving

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.democonsole.generator.DemoBerichtGenerator
import nl.rijksoverheid.moz.fbs.democonsole.generator.Organisatie
import nl.rijksoverheid.moz.fbs.democonsole.generator.Persona
import nl.rijksoverheid.moz.fbs.democonsole.generator.Sjabloon
import nl.rijksoverheid.moz.fbs.democonsole.storing.ToxiproxyRegister
import nl.rijksoverheid.moz.fbs.demopersonas.DemoPersona
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaBron
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class OmgevingResourceTest {

    private fun persona(id: String) = DemoPersona(
        id = id,
        label = id.replaceFirstChar { it.uppercase() },
        type = "BSN",
        waarde = "999993653",
        magazijnen = emptyList(),
        bron = PersonaBron.KETEN,
    )

    // Geen mockk<DemoBerichtGenerator>(): de klasse is niet @ApplicationScoped en dus finaal, en
    // MockK kan finale klassen alleen via zijn inline-agent aan, die deze module niet gebruikt.
    private fun generator(vararg doelen: Pair<String, String>) = DemoBerichtGenerator(
        personas = doelen.map { (id, label) ->
            Persona(id, label, "BSN", "999993653", listOf(RVO))
        },
        organisaties = mapOf(RVO to Organisatie(RVO, "RVO", listOf(Sjabloon("Onderwerp", "Inhoud.")))),
        klok = Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC),
    )

    private fun resource(
        basis: String?,
        vararg proxies: String,
        simulator: Boolean = true,
        sessiecache: Boolean = true,
        berichtenbox: String? = null,
        personas: List<DemoPersona> = emptyList(),
        doelgroep: DemoBerichtGenerator = generator("pietersen" to "J. Pietersen"),
    ): OmgevingResource {
        val config = mockk<OmgevingConfig> {
            every { uitvraagBasis() } returns Optional.ofNullable(basis)
            every { sessiecache() } returns sessiecache
            every { simulator() } returns simulator
            every { berichtenboxUrl() } returns Optional.ofNullable(berichtenbox)
        }
        val register = mockk<ToxiproxyRegister> { every { namen() } returns proxies.toSet() }
        val personaService = mockk<PersonaService> { every { alle() } returns personas }

        return OmgevingResource(config, register, personaService, doelgroep)
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
    fun `de persona-lijst komt mee in het antwoord`() {
        // Het paneel en de wegwerp-berichtenbox lezen hem hieruit, dus een lege lijst is iets
        // anders dan een ontbrekend veld: het eerste betekent "niets ingericht", het tweede
        // "deze module is niet bij te werken".
        assertEquals(emptyList<String>(), resource(null).omgeving().personas.map { it.id })

        assertEquals(
            listOf("pietersen", "vandijk"),
            resource(null, personas = listOf(persona("pietersen"), persona("vandijk"))).omgeving().personas.map { it.id },
        )
    }

    @Test
    fun `berichtPersonas draagt de persona's waarvoor de console kan aanleveren`() {
        // Naast `personas` en niet erin: die lijst is het contract met een berichtenbox en draagt
        // ook persona's zonder magazijn. Levert de console voor zo iemand aan, dan weigert het
        // magazijn met 403 — dus het paneel hoort hem niet als keuze te tonen.
        assertEquals(
            listOf("pietersen" to "J. Pietersen", "bakkerij" to "Bakkerij De Vroege Vogel"),
            resource(null, doelgroep = generator("pietersen" to "J. Pietersen", "bakkerij" to "Bakkerij De Vroege Vogel"))
                .omgeving().berichtPersonas.map { it.id to it.label },
        )
    }

    @Test
    fun `berichtPersonas met precies één persona levert een lijst met dat ene element`() {
        assertEquals(listOf("pietersen"), resource(null).omgeving().berichtPersonas.map { it.id })
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

    private companion object {

        const val RVO = "00000000000000100000"
    }
}
