package nl.rijksoverheid.moz.berichtenlijst.berichten

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class BerichtenOphalenResourceTest {

    @BeforeEach
    fun setUp() {
        MockMagazijnClientFactory.shouldFail = false
    }

    @Test
    fun `GET ophalen retourneert SSE-stream met magazijn events`() {
        val response = given()
            .queryParam("ontvanger", "999993653")
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assert(response.contains("\"event\":\"magazijn-status\"")) { "Verwacht magazijn-status events in: $response" }
        assert(response.contains("\"event\":\"ophalen-gereed\"")) { "Verwacht ophalen-gereed event in: $response" }
        assert(response.contains("\"status\":\"BEZIG\"")) { "Verwacht BEZIG status in: $response" }
        assert(response.contains("\"status\":\"OK\"")) { "Verwacht OK status in: $response" }
    }

    @Test
    fun `GET ophalen bij fout toont FOUT status`() {
        MockMagazijnClientFactory.shouldFail = true

        val response = given()
            .queryParam("ontvanger", "999993653")
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assert(response.contains("\"status\":\"FOUT\"")) { "Verwacht FOUT status in: $response" }
        assert(response.contains("\"mislukt\":1")) { "Verwacht mislukt=1 in: $response" }
    }

    @Test
    fun `GET berichten na mislukt ophalen retourneert 200 met GEREED status en lege lijst`() {
        MockMagazijnClientFactory.shouldFail = true
        val ontvanger = "fout-test-${System.nanoTime()}"

        // Ophalen met fout
        given()
            .queryParam("ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)

        // Berichten ophalen — status is GEREED maar lijst is leeg
        given()
            .queryParam("ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten.size()", `is`(0))
            .body("_aggregatie.status", `is`("GEREED"))
            .body("_aggregatie.mislukt", `is`(1))
            .body("_aggregatie.geslaagd", `is`(0))
    }

    @Test
    fun `berichten in cache na ophalen`() {
        // Eerst ophalen om cache te vullen
        val sseResponse = given()
            .queryParam("ontvanger", "cache-test")
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        // Verifieer dat ophalen-gereed event is ontvangen (cache is gevuld)
        assert(sseResponse.contains("\"event\":\"ophalen-gereed\"")) {
            "SSE response bevat geen ophalen-gereed event: $sseResponse"
        }

        // Dan berichten ophalen uit cache
        val berichtenResponse = given()
            .queryParam("ontvanger", "cache-test")
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .extract().body().asString()

        // Debug output bij falen
        assert(berichtenResponse.contains("\"totalElements\":3")) {
            "Berichten response had niet de verwachte berichten: $berichtenResponse\nSSE was: $sseResponse"
        }
    }
}
