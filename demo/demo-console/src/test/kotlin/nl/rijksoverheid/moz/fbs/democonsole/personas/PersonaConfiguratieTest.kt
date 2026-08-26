package nl.rijksoverheid.moz.fbs.democonsole.personas

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.democonsole.generator.DemoBerichtGenerator
import org.junit.jupiter.api.Assertions.assertEquals
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

    @TestHTTPResource("/api/demo/personas")
    lateinit var personasUrl: URL

    @Test
    fun `levert de ingerichte persona's met hun ontvanger-header en bron`() {
        assertEquals(
            listOf(
                Triple("bakkerij", "BSN:999996666", PersonaBron.KETEN),
                Triple("vandijk", "KVK:12345678", PersonaBron.KETEN),
                Triple("grootbedrijf", "KVK:90000001", PersonaBron.KETEN),
                Triple("pietersen", "BSN:999993653", PersonaBron.KETEN),
            ),
            personaService.alle().map { Triple(it.id, it.ontvanger, it.bron) },
        )
    }

    @Test
    fun `de handmatige testparser leest hetzelfde als de configuratie-mapping`() {
        assertEquals(TestPersonas.uitApplicationProperties().alle(), personaService.alle())
    }

    @Test
    fun `laat de generator alleen persona's opvoeren die bij een organisatie horen`() {
        assertEquals(listOf("bakkerij", "vandijk", "pietersen"), personaService.metMagazijnen().map { it.id })
    }

    @Test
    fun `de generator komt met de echte configuratie door zijn eigen invarianten heen`() {
        // Injectie dwingt de bean af; zijn init-blok toetst de opt-in-OIN's tegen de organisaties.
        assertTrue(generator.genereer(aantal = 5, random = Random(1)).isNotEmpty())
    }

    @Test
    fun `levert de keuzelijst als JSON, met de bron in kleine letters`() {
        val respons = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(personasUrl.toURI()).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, respons.statusCode())
        assertTrue(respons.headers().firstValue("content-type").orElse("").startsWith("application/json"))

        val geleverd = ObjectMapper().readTree(respons.body()).map {
            listOf(it.path("id").asText(), it.path("label").asText(), it.path("ontvanger").asText(), it.path("bron").asText())
        }

        assertEquals(
            personaService.alle().map { listOf(it.id, it.label, it.ontvanger, it.bron.name.lowercase()) },
            geleverd,
        )
    }
}
