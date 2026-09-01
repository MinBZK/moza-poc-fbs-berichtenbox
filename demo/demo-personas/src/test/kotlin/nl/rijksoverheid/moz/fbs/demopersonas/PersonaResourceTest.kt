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

        // Op de geparste boom en niet op de ruwe tekst: het contract is het veld met zijn waarde,
        // niet de byte-vorm die Jackson er toevallig van maakt.
        val personas = ObjectMapper().readTree(respons.body())
        val vandijk = personas.first { it.path("ontvanger").asText() == "KVK:90000014" }

        assertEquals("keten", vandijk.path("bron").asText())
    }

    @Test
    fun `wat de lijst teruggeeft is wat er in de configuratie staat`() {
        // Sluit de keten binnen deze module: de handgeschreven parser en de mapping van SmallRye
        // lezen hetzelfde bestand, en dit endpoint levert af wat die mapping oplevert.
        val verwacht = TestPersonas.uitConfiguratie().alle().map { it.id }
        val geleverd = ObjectMapper().readTree(haal().body()).map { it.path("id").asText() }

        assertEquals(verwacht, geleverd)
    }

    @Test
    fun `deze dienst draagt alleen die ene leeslijst`() {
        // De bestaansreden van de module: wat er niet in zit, kan niet per ongeluk bereikbaar
        // worden. Komt hier ooit een endpoint van de console bij, dan hoort dat op te vallen.
        assertEquals(404, statusVan("/api/demo/omgeving"))
        assertEquals(404, statusVan("/api/demo/storing"))
    }

    @Test
    fun `de lijst is niet te wijzigen`() {
        assertEquals(405, HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(url.toURI()).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode())
    }

    @Test
    fun `het antwoord mag niet bewaard worden`() {
        // De lijst verandert met de inrichting van de demo; een hergebruikt antwoord toont een
        // testaccount dat er niet meer is. Op een gedeelde omgeving zit er bovendien een ingress
        // tussen die zich aan deze header houdt.
        assertEquals(
            "no-store",
            haal().headers().firstValue("Cache-Control").orElse(null),
        )
    }

    private fun statusVan(pad: String): Int = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(url.toURI().resolve(pad)).GET().build(),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

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
