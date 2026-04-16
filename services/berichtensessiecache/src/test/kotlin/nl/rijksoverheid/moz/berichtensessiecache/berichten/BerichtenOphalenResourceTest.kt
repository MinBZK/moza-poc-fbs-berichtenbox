package nl.rijksoverheid.moz.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
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
        (berichtenCache as MockBerichtenCache).clear()
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
            .header("X-Ontvanger", "999993653")
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
            .header("X-Ontvanger", "999993653")
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
        val ontvanger = "fout-test-${System.nanoTime()}"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .header("X-Ontvanger", ontvanger)
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
        val sseResponse = given()
            .header("X-Ontvanger", "cache-test")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(
            sseResponse.contains("\"event\":\"ophalen-gereed\""),
            "SSE response bevat geen ophalen-gereed event: $sseResponse",
        )

        val berichtenResponse = given()
            .header("X-Ontvanger", "cache-test")
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
            .header("X-Ontvanger", "multi-ok-${System.nanoTime()}")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"totaalMagazijnen\":2"), "Verwacht totaalMagazijnen=2 in: $response")
        assertTrue(response.contains("\"geslaagd\":2"), "Verwacht geslaagd=2 in: $response")
    }

    @Test
    fun `GET ophalen terwijl aggregatie al bezig is retourneert 409`() {
        val ontvanger = "conflict-test-${System.nanoTime()}"
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        (berichtenCache as MockBerichtenCache).simuleerBezig(cacheKey)

        given()
            .header("X-Ontvanger", ontvanger)
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
            .header("X-Ontvanger", "partial-fail-${System.nanoTime()}")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"totaalMagazijnen\":2"), "Verwacht totaalMagazijnen=2 in: $response")
        assertTrue(response.contains("\"geslaagd\":1"), "Verwacht geslaagd=1 in: $response")
        assertTrue(response.contains("\"mislukt\":1"), "Verwacht mislukt=1 in: $response")
    }
}
