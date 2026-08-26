package nl.rijksoverheid.moz.fbs.democonsole.personas

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Toetst de ingerichte persona's zoals de module ze bij het starten leest. Een unit-test met een
 * eigen configuratie-object dekt de mapping van `demo.personas.*` op de interface niet: juist
 * daar zit de kans op een typfout die pas bij het opstarten van de demo blijkt.
 */
@QuarkusTest
class PersonaConfiguratieTest {

    @Inject
    lateinit var personaService: PersonaService

    @TestHTTPResource("/api/demo/personas")
    lateinit var personasUrl: URL

    @Test
    fun `levert de ingerichte persona's met hun ontvanger-header`() {
        assertEquals(
            listOf(
                "bakkerij" to "BSN:999996666",
                "vandijk" to "KVK:12345678",
                "grootbedrijf" to "KVK:90000001",
                "pietersen" to "BSN:999993653",
            ),
            personaService.alle().map { it.id to it.ontvanger },
        )
    }

    @Test
    fun `laat de generator alleen persona's opvoeren die bij een organisatie horen`() {
        assertEquals(listOf("bakkerij", "vandijk", "pietersen"), personaService.metMagazijnen().map { it.id })
    }

    @Test
    fun `levert de keuzelijst als JSON, met de bron in kleine letters`() {
        val respons = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(personasUrl.toURI()).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, respons.statusCode())

        val eerste = ObjectMapper().readTree(respons.body()).first()

        assertEquals("bakkerij", eerste.path("id").asText())
        assertEquals("Bakkerij De Vroege Vogel", eerste.path("label").asText())
        assertEquals("BSN:999996666", eerste.path("ontvanger").asText())
        assertEquals("keten", eerste.path("bron").asText())
    }
}
