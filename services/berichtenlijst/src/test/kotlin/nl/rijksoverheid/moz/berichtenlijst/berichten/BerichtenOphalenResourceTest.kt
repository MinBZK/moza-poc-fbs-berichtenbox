package nl.rijksoverheid.moz.berichtenlijst.berichten

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class BerichtenOphalenResourceTest {

    @BeforeEach
    fun setUp() {
        MockMagazijnClientFactory.shouldFailA = false
        MockMagazijnClientFactory.shouldFailB = false
        MockMagazijnClientFactory.shouldTimeoutA = false
        MockMagazijnClientFactory.shouldTimeoutB = false
    }

    @Test
    fun `GET ophalen zonder ontvanger retourneert 400`() {
        given()
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
    }

    @Test
    fun `GET ophalen retourneert SSE-stream met magazijn events`() {
        val response = given()
            .queryParam("ontvanger", "999993653")
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"event\":\"magazijn-status\""), "Verwacht magazijn-status events in: $response")
        assertTrue(response.contains("\"event\":\"ophalen-gereed\""), "Verwacht ophalen-gereed event in: $response")
        assertTrue(response.contains("\"status\":\"BEZIG\""), "Verwacht BEZIG status in: $response")
        assertTrue(response.contains("\"status\":\"OK\""), "Verwacht OK status in: $response")
    }

    @Test
    fun `GET ophalen bij fout toont FOUT status`() {
        MockMagazijnClientFactory.shouldFailA = true
        MockMagazijnClientFactory.shouldFailB = true

        val response = given()
            .queryParam("ontvanger", "999993653")
            .`when`().get("/api/v1/berichten/ophalen")
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
            .queryParam("ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)

        given()
            .queryParam("ontvanger", ontvanger)
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
            .queryParam("ontvanger", "cache-test")
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(
            sseResponse.contains("\"event\":\"ophalen-gereed\""),
            "SSE response bevat geen ophalen-gereed event: $sseResponse",
        )

        val berichtenResponse = given()
            .queryParam("ontvanger", "cache-test")
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
            .queryParam("ontvanger", "multi-ok-${System.nanoTime()}")
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"totaalMagazijnen\":2"), "Verwacht totaalMagazijnen=2 in: $response")
        assertTrue(response.contains("\"geslaagd\":2"), "Verwacht geslaagd=2 in: $response")
    }

    @Test
    fun `GET ophalen partial failure magazijn-a OK magazijn-b faalt`() {
        MockMagazijnClientFactory.shouldFailB = true

        val response = given()
            .queryParam("ontvanger", "partial-fail-${System.nanoTime()}")
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"totaalMagazijnen\":2"), "Verwacht totaalMagazijnen=2 in: $response")
        assertTrue(response.contains("\"geslaagd\":1"), "Verwacht geslaagd=1 in: $response")
        assertTrue(response.contains("\"mislukt\":1"), "Verwacht mislukt=1 in: $response")
    }
}
