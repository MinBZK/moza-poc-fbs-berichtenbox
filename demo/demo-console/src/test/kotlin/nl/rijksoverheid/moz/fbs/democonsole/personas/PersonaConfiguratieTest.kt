package nl.rijksoverheid.moz.fbs.democonsole.personas

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.Startup
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.democonsole.generator.DemoBerichtGenerator
import nl.rijksoverheid.moz.fbs.demopersonas.MagazijnKennis
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService
import nl.rijksoverheid.moz.fbs.demopersonas.TestPersonas
import nl.rijksoverheid.moz.fbs.democonsole.generator.GeneratorProducer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.random.Random

/**
 * Toetst de ingerichte persona's zoals de module ze bij het starten leest. Een unit-test met een
 * eigen configuratie-object dekt de mapping van `demo.personas.*` op de interface niet: juist
 * daar zit de kans op een typfout die pas bij het opstarten van de demo blijkt.
 */
@QuarkusTest
class PersonaConfiguratieTest {

    @Inject
    lateinit var personaService: PersonaService

    @Inject
    lateinit var generator: DemoBerichtGenerator

    @TestHTTPResource("/api/demo/omgeving")
    lateinit var omgevingUrl: URL

    @Test
    fun `levert de ingerichte persona's in de volgorde van de keuzelijst`() {
        assertEquals(
            listOf(
                "bakkerij", "proeftuin-een", "proeftuin-twee", "proeftuin-drie",
                "vandijk", "grootbedrijf", "pietersen", "concern",
            ),
            personaService.alle().map { it.id },
        )
    }

    @Inject
    lateinit var magazijnKennis: Instance<MagazijnKennis>

    @Test
    fun `de personadienst krijgt de magazijn-kennis van deze module aangereikt`() {
        // De naad loopt alleen via CDI: niets injecteert MagazijnKennisUitInrichting rechtstreeks.
        // Valt @ApplicationScoped weg of breekt de Jandex-index, dan verdwijnt de kruiscontrole
        // zonder dat een unittest aan beide kanten dat merkt — die bouwen hun eigen dubbels.
        assertTrue(magazijnKennis.isResolvable, "geen MagazijnKennis-implementatie beschikbaar")
        assertTrue(magazijnKennis.get() is MagazijnKennisUitInrichting, magazijnKennis.get()::class.java.name)
    }

    @Test
    fun `elke ingerichte persona wijst naar een magazijn waarvoor hier een aanlever-URL staat`() {
        // Wat de naad in de praktijk bewaakt, op de échte inrichting. De unittests toetsen de
        // implementatie met verzonnen OIN's; deze toetst dat de twee configuratiebestanden
        // daadwerkelijk op elkaar aansluiten.
        personaService.alle().forEach { persona ->
            persona.magazijnen.forEach { oin ->
                assertNull(magazijnKennis.get().bezwaarTegen(oin), "persona '${persona.id}' wijst naar $oin")
            }
        }
    }

    @Test
    fun `er is minstens één persona met een BSN, want de ontdubbeling draait daarop`() {
        // `bediening.js` filtert de keuzelijst van de ontdubbeling op `BSN:`. Zonder zo'n persona
        // staat die knop stil uit, met een melding die naar de inrichting wijst.
        assertTrue(personaService.alle().any { it.type == "BSN" })
    }

    @Test
    fun `beide beans worden bij het starten gebouwd, niet pas bij de eerste aanroep`() {
        // Zonder deze assertie kan @Startup verdwijnen zonder dat één test rood wordt: injectie
        // bouwt de bean toch wel, dus geen enkele andere test merkt het verschil.
        assertTrue(PersonaService::class.java.isAnnotationPresent(Startup::class.java))
        assertTrue(
            GeneratorProducer::class.java
                .getDeclaredMethod("generator", PersonaService::class.java)
                .isAnnotationPresent(Startup::class.java),
        )
    }

    @Test
    fun `de handmatige testparser leest hetzelfde als de configuratie-mapping`() {
        assertEquals(TestPersonas.uitConfiguratie().alle(), personaService.alle())
    }

    @Test
    fun `laat de generator alleen persona's opvoeren die bij een organisatie horen`() {
        assertEquals(
            listOf("bakkerij", "proeftuin-een", "proeftuin-twee", "proeftuin-drie", "vandijk", "pietersen"),
            personaService.metMagazijnen().map { it.id },
        )
        assertEquals(
            listOf("grootbedrijf", "concern"),
            (personaService.alle() - personaService.metMagazijnen().toSet()).map { it.id },
        )
    }

    @Test
    fun `de generator komt met de echte configuratie door zijn eigen invarianten heen`() {
        // Injectie dwingt de bean af; zijn init-blok toetst de opt-in-OIN's tegen de organisaties.
        assertTrue(generator.genereer(aantal = 5, random = Random(1)).isNotEmpty())
    }

    @Test
    fun `levert de keuzelijst mee in de omgeving, met de bron in kleine letters`() {
        // Via /api/demo/omgeving: het personas-adres hoort bij de personadienst, en deze module
        // beantwoordt het bewust niet. De twee pagina's die zij wél serveert lezen de lijst hier.
        val respons = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(omgevingUrl.toURI()).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, respons.statusCode())
        assertTrue(respons.headers().firstValue("content-type").orElse("").startsWith("application/json"))

        val geleverd = ObjectMapper().readTree(respons.body()).path("personas").map {
            listOf(it.path("id").asText(), it.path("label").asText(), it.path("ontvanger").asText(), it.path("bron").asText())
        }

        assertEquals(
            personaService.alle().map { listOf(it.id, it.label, it.ontvanger, it.bron.name.lowercase()) },
            geleverd,
        )
    }
}
