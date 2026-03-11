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
        MockMagazijnClientFactory.shouldFailA = false
        MockMagazijnClientFactory.shouldFailB = false
        MockMagazijnClientFactory.shouldTimeoutA = false
        MockMagazijnClientFactory.shouldTimeoutB = false
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
        given()
            .queryParam("ontvanger", "test-paginering")
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)

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
            .body("totalElements", `is`(4))
            .body("totalPages", `is`(2))
            .body("_aggregatie.status", `is`("GEREED"))
            .body("_links.self.href", containsString("page=0"))
            .body("_links.self.href", containsString("ontvanger=test-paginering"))
            .body("_links.first.href", notNullValue())
            .body("_links.last.href", notNullValue())
            .body("_links.next.href", containsString("page=1"))
    }

    @Test
    fun `GET berichten paginering page 1 geeft andere resultaten`() {
        given()
            .queryParam("ontvanger", "test-page1")
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)

        given()
            .queryParam("ontvanger", "test-page1")
            .queryParam("page", 1)
            .queryParam("pageSize", 2)
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten.size()", `is`(2))
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

    @Test
    fun `GET bericht by id retourneert bericht met correcte velden`() {
        given()
            .`when`().get("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(200)
            .body("berichtId", `is`("11111111-1111-1111-1111-111111111111"))
            .body("afzender", `is`("00000001234567890000"))
            .body("ontvanger", `is`("999993653"))
            .body("onderwerp", `is`("Test bericht 1"))
            .body("magazijnId", `is`("magazijn-a"))
    }

    @Test
    fun `GET zoeken retourneert gefilterde resultaten`() {
        val ontvanger = "zoek-test-${System.nanoTime()}"

        given()
            .queryParam("ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)

        given()
            .queryParam("q", "bericht 1")
            .queryParam("ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/zoeken")
            .then()
            .statusCode(200)
            .body("berichten.size()", `is`(1))
            .body("totalElements", `is`(1))
    }

    @Test
    fun `GET zoeken op afzender retourneert gefilterde resultaten`() {
        val ontvanger = "zoek-afzender-${System.nanoTime()}"

        given()
            .queryParam("ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/ophalen")
            .then()
            .statusCode(200)

        // Zoek op afzender OIN van magazijn-b bericht
        given()
            .queryParam("q", "00000005555555550000")
            .queryParam("ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/zoeken")
            .then()
            .statusCode(200)
            .body("berichten.size()", `is`(1))
            .body("berichten[0].magazijnId", `is`("magazijn-b"))
    }

    @Test
    fun `GET bericht by id retourneert 502 als alle magazijnen falen`() {
        MockMagazijnClientFactory.shouldFailA = true
        MockMagazijnClientFactory.shouldFailB = true

        given()
            .`when`().get("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(502)
            .contentType("application/problem+json")
            .body("status", `is`(502))
    }

    @Test
    fun `GET bericht by id retourneert bericht als magazijn-a faalt maar magazijn-b slaagt`() {
        MockMagazijnClientFactory.shouldFailA = true

        given()
            .`when`().get("/api/v1/berichten/44444444-4444-4444-4444-444444444444")
            .then()
            .statusCode(200)
            .body("berichtId", `is`("44444444-4444-4444-4444-444444444444"))
            .body("magazijnId", `is`("magazijn-b"))
    }

    @Test
    fun `GET bericht by id retourneert 404 als magazijn-a faalt en bericht niet in magazijn-b`() {
        MockMagazijnClientFactory.shouldFailA = true

        given()
            .`when`().get("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", `is`(404))
    }
}
