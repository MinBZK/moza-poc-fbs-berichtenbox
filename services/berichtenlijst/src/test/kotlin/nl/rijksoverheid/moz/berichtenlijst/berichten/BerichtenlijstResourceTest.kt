package nl.rijksoverheid.moz.berichtenlijst.berichten

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class BerichtenlijstResourceTest {

    @Inject
    lateinit var berichtenCache: BerichtenCache

    @BeforeEach
    fun setUp() {
        MockMagazijnClientFactory.shouldFail = false
    }

    @Test
    fun `GET berichten retourneert 409 als ophalen niet is aangeroepen`() {
        given()
            .queryParam("ontvanger", "onbekend-${System.nanoTime()}")
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("status", `is`(409))
            .body("detail", containsString("nog niet opgehaald"))
    }

    @Test
    fun `GET berichten retourneert 409 als ophalen nog bezig is`() {
        val ontvanger = "bezig-test-${System.nanoTime()}"
        val key = BerichtenCache.cacheKey(ontvanger)

        // Simuleer een lopend ophaalproces door BEZIG status in cache te zetten
        berichtenCache.storeAggregationStatus(key, AggregationStatus(status = OphalenStatus.BEZIG, totaalMagazijnen = 1))
            .await().indefinitely()

        given()
            .queryParam("ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("status", `is`(409))
            .body("detail", containsString("momenteel opgehaald"))
    }

    @Test
    fun `GET berichten retourneert gepagineerde resultaten na ophalen`() {
        // Eerst ophalen om cache te vullen
        given()
            .queryParam("ontvanger", "test-paginering")
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)

        // Dan berichten ophalen met paginering
        given()
            .queryParam("ontvanger", "test-paginering")
            .queryParam("page", 0)
            .queryParam("pageSize", 2)
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .header("API-Version", `is`("0.1.0"))
            .body("berichten.size()", `is`(2))
            .body("page", `is`(0))
            .body("pageSize", `is`(2))
            .body("totalElements", `is`(3))
            .body("totalPages", `is`(2))
            .body("_aggregatie.status", `is`("GEREED"))
    }

    @Test
    fun `GET berichten paginering page 1 geeft andere resultaten`() {
        // Eerst ophalen om cache te vullen
        given()
            .queryParam("ontvanger", "test-page1")
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)

        // Page 1 met pageSize 2 geeft 1 resultaat (3 berichten totaal)
        given()
            .queryParam("ontvanger", "test-page1")
            .queryParam("page", 1)
            .queryParam("pageSize", 2)
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten.size()", `is`(1))
            .body("page", `is`(1))
    }

    @Test
    fun `GET bericht by ongeldig ID retourneert 404`() {
        given()
            .`when`().get("/api/v1/berichten/niet-een-uuid")
            .then()
            .statusCode(404)
    }

    @Test
    fun `GET bericht by onbekend ID retourneert 404 als problem json`() {
        given()
            .`when`().get("/api/v1/berichten/00000000-0000-0000-0000-000000000000")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", `is`(404))
            .body("title", `is`("Not Found"))
    }

    @Test
    fun `GET zoeken zonder query retourneert 400 als problem json`() {
        given()
            .`when`().get("/api/v1/berichten/zoeken")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
    }

    @Test
    fun `GET zoeken met te korte query retourneert 400 als problem json`() {
        given()
            .queryParam("q", "a")
            .`when`().get("/api/v1/berichten/zoeken")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
    }
}
