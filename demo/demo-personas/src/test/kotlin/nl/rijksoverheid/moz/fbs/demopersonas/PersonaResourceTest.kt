package nl.rijksoverheid.moz.fbs.demopersonas

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * De vorm van dit antwoord is een afspraak met elke berichtenbox die de demo gebruikt: de proeftuin
 * zoekt een testaccount op `bron` en `ontvanger` (`p.bron === "keten" && p.ontvanger === "KVK:" +
 * nummer`). Verschuift een van die twee namen, dan vindt hij niets meer en meldt hij de bezoeker
 * dat de keten hem niet kent — een fout die pas tijdens een demo opvalt.
 */
@QuarkusTest
class PersonaResourceTest {

    @TestHTTPResource("/api/demo/personas")
    lateinit var url: URL

    private fun haal(): HttpResponse<String> = HttpClient.newHttpClient()
        .send(HttpRequest.newBuilder(url.toURI()).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `de lijst draagt de sleutels waarop een berichtenbox zoekt`() {
        val respons = haal()

        assertEquals(200, respons.statusCode())
        assertTrue(respons.body().contains(""""bron":"keten""""), "veld bron ontbreekt")
        assertTrue(respons.body().contains(""""ontvanger":"KVK:90000014""""), "Garage Van Dijk ontbreekt")
    }

    @Test
    fun `elke persona draagt precies de vier velden waarop een afnemer rekent`() {
        // Niet alleen de eerste: een lijst van één zou "geeft het enige element terug" niet
        // onderscheiden van "levert elke persona compleet af".
        val personas = ObjectMapper().readTree(haal().body())

        assertTrue(personas.size() > 1, "verwachtte meerdere persona's, kreeg ${personas.size()}")

        personas.forEach { persona ->
            assertEquals(
                setOf("id", "label", "ontvanger", "bron"),
                persona.fieldNames().asSequence().toSet(),
                "onverwachte velden op $persona",
            )
        }
    }
}
