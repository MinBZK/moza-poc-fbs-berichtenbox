package nl.rijksoverheid.moz.fbs.demopersonas

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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

    @Inject
    lateinit var personaService: PersonaService

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

        // Ook de wáárde van het label: het is het eerste wat een toeschouwer in de keuzelijst ziet,
        // en `naarDto()` zet vier positionele strings naast elkaar.
        assertEquals("Garage Van Dijk B.V.", vandijk.path("label").asText())
    }

    @Test
    fun `wat de lijst teruggeeft is wat er in de configuratie staat`() {
        // Sluit de keten binnen deze module: de handgeschreven parser en de mapping van SmallRye
        // lezen hetzelfde bestand, en dit endpoint levert af wat die mapping oplevert.
        val geleverd = ObjectMapper().readTree(haal().body()).map { it.path("id").asText() }

        assertEquals(personaService.alle().map { it.id }, geleverd)

        // En op de héle DemoPersona, niet alleen op de ids: `magazijnen` gaat bewust niet over de
        // lijn, dus een mapping die "OIN_A,OIN_B" ooit als één element leest valt hier nergens op —
        // terwijl de generator dan nog maar bij één magazijn aanlevert.
        assertEquals(TestPersonas.uitConfiguratie().alle(), personaService.alle())
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

    @ParameterizedTest
    @CsvSource(
        "Cache-Control, no-store",
        "X-Frame-Options, DENY",
        "X-Content-Type-Options, nosniff",
        "Referrer-Policy, no-referrer",
    )
    fun `het antwoord draagt de headers van een dienst zonder authenticatiemuur`(header: String, waarde: String) {
        // Alle vier en niet alleen no-store: ze staan in één blok in de configuratie van deze
        // module, dat op ordinal 100 ook geldt voor elke afnemer die de sleutel niet zelf zet.
        // Voor no-store speelt daarbovenop dat de lijst met de inrichting van de demo verandert —
        // een hergebruikt antwoord toont een testaccount dat er niet meer is, en op een gedeelde
        // omgeving zit er een ingress tussen die zich aan deze header houdt.
        assertEquals(waarde, haal().headers().firstValue(header).orElse(null))
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
