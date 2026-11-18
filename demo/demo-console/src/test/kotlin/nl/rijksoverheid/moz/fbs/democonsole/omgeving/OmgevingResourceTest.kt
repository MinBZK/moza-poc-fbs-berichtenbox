package nl.rijksoverheid.moz.fbs.democonsole.omgeving

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.democonsole.generator.DemoBerichtGenerator
import nl.rijksoverheid.moz.fbs.democonsole.generator.Organisatie
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

    private fun persona(id: String, waarde: String) = DemoPersona(
        id = id,
        label = id.replaceFirstChar { it.uppercase() },
        type = "BSN",
        waarde = waarde,
        magazijnen = emptyList(),
        bron = PersonaBron.KETEN,
    )

    // Een echte generator en geen mock: hij bewaakt zijn eigen invarianten, dus een testdubbel zou
    // een doelgroep kunnen teruggeven die de productie-generator nooit zou opleveren.
    //
    // Elke persona een eigen identificatienummer — hier en in `persona(...)` hierboven: gelijke
    // nummers worden in de personadienst juist fail-fast geweigerd, en een fixture die niet kan
    // bestaan bewijst niets.
    private fun generator(vararg doelen: Pair<String, String>): DemoBerichtGenerator {
        require(doelen.size <= KVK_NUMMERS.size) { "de fixture kent ${KVK_NUMMERS.size} nummers toe" }

        return DemoBerichtGenerator(
            personas = doelen.mapIndexed { volgnummer, (id, label) ->
                DemoPersona(
                    id = id,
                    label = label,
                    type = "KVK",
                    waarde = KVK_NUMMERS[volgnummer],
                    magazijnen = listOf(RVO),
                    bron = PersonaBron.KETEN,
                )
            },
            organisaties = mapOf(RVO to Organisatie(RVO, "RVO", listOf(Sjabloon("Onderwerp", "Inhoud.")))),
            klok = Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC),
        )
    }

    private fun resource(
        vararg proxies: String,
        simulator: Boolean = true,
        sessiecache: Boolean = true,
        berichtenbox: String? = null,
        personas: List<DemoPersona> = emptyList(),
        generator: DemoBerichtGenerator = generator("pietersen" to "J. Pietersen"),
    ): OmgevingResource {
        val config = mockk<OmgevingConfig> {
            every { sessiecache() } returns sessiecache
            every { simulator() } returns simulator
            every { berichtenboxUrl() } returns Optional.ofNullable(berichtenbox)
        }
        val register = mockk<ToxiproxyRegister> { every { namen() } returns proxies.toSet() }
        val personaService = mockk<PersonaService> { every { alle() } returns personas }

        return OmgevingResource(config, register, personaService, generator)
    }

    @Test
    fun `storingen spiegelt het register, gesorteerd`() {
        assertEquals(
            listOf("aanmeld", "profiel", "redis"),
            resource("redis", "profiel", "aanmeld").omgeving().storingen,
        )
    }

    @Test
    fun `een omgeving zonder storingen levert een lege lijst en geen fout`() {
        assertEquals(emptyList<String>(), resource().omgeving().storingen)
    }

    @Test
    fun `een omgeving met precies één storing levert een lijst met dat ene element`() {
        // Onderscheidt "geeft het enige element terug" van "discrimineert per naam" — een lijst
        // van meerdere elementen dekt dat verschil niet.
        assertEquals(listOf("redis"), resource("redis").omgeving().storingen)
    }

    @Test
    fun `een ingerichte simulator komt als true door`() {
        // Het paneel moet vooraf weten of deze omgeving gesimuleerde magazijnen kent, anders leest
        // een mislukte uitlezing als "niet ingericht".
        assertEquals(true, resource().omgeving().simulator)
    }

    @Test
    fun `een omgeving zonder simulator meldt false zodat de pagina die knoppen weglaat`() {
        assertEquals(false, resource(simulator = false).omgeving().simulator)
    }

    @Test
    fun `een bereikbare sessiecache komt als true door`() {
        assertEquals(true, resource().omgeving().sessiecache)
    }

    @Test
    fun `een onbereikbare sessiecache komt als false door zodat de pagina de knop weglaat`() {
        // De sessiecache staat in een ander project dan de console. Waar het verkeer daarheen niet
        // openstaat, geeft de knop gegarandeerd een fout; hem tonen kost tijdens een demo uitleg
        // die niets toevoegt.
        assertEquals(false, resource(sessiecache = false).omgeving().sessiecache)
    }

    @Test
    fun `de persona-lijst komt mee in het antwoord`() {
        // Het paneel leest hem hieruit, dus een lege lijst is iets anders dan een ontbrekend veld:
        // het eerste betekent "niets ingericht", het tweede "deze module is niet bij te werken".
        assertEquals(emptyList<String>(), resource().omgeving().personas.map { it.id })

        // Met het label erbij: een omzetting die de id in beide velden zet, compileert en toont een
        // keuzelijst van sleutels.
        assertEquals(
            listOf("pietersen" to "Pietersen", "vandijk" to "Vandijk"),
            resource(
                personas = listOf(persona("pietersen", "999993653"), persona("vandijk", "999996666")),
            ).omgeving().personas.map { it.id to it.label },
        )
    }

    @Test
    fun `berichtPersonas draagt de persona's waarvoor de console kan aanleveren`() {
        // Op volgorde en met beide velden: een verwisseling van id en label compileert, en levert
        // een keuzelijst die er normaal uitziet terwijl elke keuze een 404 geeft.
        assertEquals(
            listOf("pietersen" to "J. Pietersen", "bakkerij" to "Bakkerij De Vroege Vogel"),
            resource(generator = generator("pietersen" to "J. Pietersen", "bakkerij" to "Bakkerij De Vroege Vogel"))
                .omgeving().berichtPersonas.map { it.id to it.label },
        )
    }

    @Test
    fun `berichtPersonas met precies één persona levert een lijst met dat ene element`() {
        assertEquals(listOf("pietersen"), resource().omgeving().berichtPersonas.map { it.id })
    }

    @Test
    fun `zonder geconfigureerde berichtenbox blijft het veld leeg zodat het paneel het eigen pad probeert`() {
        // Lokaal zet de demo-proxy de berichtenbox op dezelfde origin; daar is een adres uit de
        // configuratie niet alleen overbodig maar ook fout zodra iemand de stack op een ander
        // adres opent.
        assertEquals("", resource().omgeving().berichtenboxUrl)
    }

    @Test
    fun `een geconfigureerde berichtenbox komt ongewijzigd door`() {
        val url = "https://proeftuin-demo-mpfm-w3h.example/moza/berichtenbox/"

        assertEquals(url, resource(berichtenbox = url).omgeving().berichtenboxUrl)
    }

    private companion object {

        const val RVO = "00000000000000100000"

        /** Geldige, onderling verschillende KVK-nummers; ze begrenzen hoeveel doelen de fixture aankan. */
        val KVK_NUMMERS = listOf("12345678", "87654321")
    }
}
