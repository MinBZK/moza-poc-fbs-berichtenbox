package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.containsString
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class BerichtenOphalenResourceTest {

    @Inject
    lateinit var berichtenCache: BerichtenCache

    @BeforeEach
    fun setUp() {
        MockMagazijnClientFactory.shouldFailA = false
        MockMagazijnClientFactory.shouldFailB = false
        MockMagazijnClientFactory.shouldTimeoutA = false
        MockMagazijnClientFactory.shouldTimeoutB = false
        MockMagazijnClientFactory.shouldHttpFailA = null
        MockMagazijnClientFactory.shouldHttpFailB = null
        (berichtenCache as MockBerichtenCache).clear()
    }

    // Genereer een unieke geldige OIN per test (20 cijfers, niet geheel nullen).
    private fun uniqueOin(): String {
        val nanos = System.nanoTime().toString().takeLast(19).padStart(19, '0')
        return "OIN:0$nanos"
    }

    @Test
    fun `GET ophalen zonder ontvanger retourneert 400`() {
        given()
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
            .body("detail", containsString("X-Ontvanger"))
    }

    @Test
    fun `GET ophalen met lege X-Ontvanger header retourneert 400`() {
        given()
            .header("X-Ontvanger", "")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
            .body("detail", containsString("X-Ontvanger"))
    }

    @Test
    fun `GET ophalen retourneert SSE-stream met magazijn events`() {
        val response = given()
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"event\":\"magazijn-bevraging-gestart\""), "Verwacht magazijn-bevraging-gestart events in: $response")
        assertTrue(response.contains("\"event\":\"magazijn-bevraging-voltooid\""), "Verwacht magazijn-bevraging-voltooid events in: $response")
        assertTrue(response.contains("\"event\":\"ophalen-gereed\""), "Verwacht ophalen-gereed event in: $response")
        assertTrue(response.contains("\"status\":\"OK\""), "Verwacht OK status in: $response")
    }

    @Test
    fun `GET ophalen bij fout toont FOUT status`() {
        MockMagazijnClientFactory.shouldFailA = true
        MockMagazijnClientFactory.shouldFailB = true

        val response = given()
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"status\":\"FOUT\""), "Verwacht FOUT status in: $response")
        assertTrue(response.contains("\"mislukt\":2"), "Verwacht mislukt=2 in: $response")
    }

    @Test
    fun `GET berichten na mislukt ophalen retourneert 200 met GEREED status en lege lijst`() {
        MockMagazijnClientFactory.shouldFailA = true
        MockMagazijnClientFactory.shouldFailB = true
        val ontvangerHeader = uniqueOin()

        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten.size()", `is`(0))
            .body("_aggregatie.status", `is`("GEREED"))
            .body("_aggregatie.mislukt", `is`(2))
            .body("_aggregatie.geslaagd", `is`(0))
    }

    @Test
    fun `berichten in cache na ophalen`() {
        val ontvangerHeader = "BSN:999991401"

        val sseResponse = given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(
            sseResponse.contains("\"event\":\"ophalen-gereed\""),
            "SSE response bevat geen ophalen-gereed event: $sseResponse",
        )

        val berichtenResponse = given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(
            berichtenResponse.contains("\"totalElements\":4"),
            "Berichten response had niet de verwachte berichten: $berichtenResponse\nSSE was: $sseResponse",
        )
    }

    @Test
    fun `GET ophalen multi-magazijn aggregatie beide OK`() {
        val response = given()
            .header("X-Ontvanger", uniqueOin())
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"totaalMagazijnen\":2"), "Verwacht totaalMagazijnen=2 in: $response")
        assertTrue(response.contains("\"geslaagd\":2"), "Verwacht geslaagd=2 in: $response")
    }

    @Test
    fun `GET ophalen terwijl aggregatie al bezig is retourneert 409`() {
        val ontvangerHeader = uniqueOin()
        val cacheKey = BerichtenCache.cacheKey(Identificatienummer.fromHeader(ontvangerHeader))
        (berichtenCache as MockBerichtenCache).simuleerBezig(cacheKey)

        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("status", `is`(409))
    }

    @Test
    fun `GET ophalen partial failure magazijn-a OK magazijn-b faalt`() {
        MockMagazijnClientFactory.shouldFailB = true

        val response = given()
            .header("X-Ontvanger", uniqueOin())
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"totaalMagazijnen\":2"), "Verwacht totaalMagazijnen=2 in: $response")
        assertTrue(response.contains("\"geslaagd\":1"), "Verwacht geslaagd=1 in: $response")
        assertTrue(response.contains("\"mislukt\":1"), "Verwacht mislukt=1 in: $response")
    }

    @Test
    fun `GET ophalen met magazijn 5xx toont FOUT met tijdelijk-bericht`() {
        MockMagazijnClientFactory.shouldHttpFailA = 503
        MockMagazijnClientFactory.shouldHttpFailB = 503

        val response = given()
            .header("X-Ontvanger", uniqueOin())
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"status\":\"FOUT\""), "Verwacht FOUT status in: $response")
        assertTrue(response.contains("tijdelijk niet bereikbaar"), "Verwacht 5xx-bericht in: $response")
    }

    @Test
    fun `GET ophalen met magazijn 4xx toont FOUT met configuratiefout-bericht`() {
        MockMagazijnClientFactory.shouldHttpFailA = 403
        MockMagazijnClientFactory.shouldHttpFailB = 403

        val response = given()
            .header("X-Ontvanger", uniqueOin())
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"status\":\"FOUT\""), "Verwacht FOUT status in: $response")
        // 4xx krijgt aparte foutmelding (configuratie/auth) zodat operator dit niet als
        // transient verwart. Generieke "kon niet geraadpleegd worden" is voor onbekende fouten.
        assertTrue(
            response.contains("aanvraag geweigerd") && response.contains("configuratiefout"),
            "Verwacht 4xx-configuratiefout-bericht in: $response",
        )
    }
}
