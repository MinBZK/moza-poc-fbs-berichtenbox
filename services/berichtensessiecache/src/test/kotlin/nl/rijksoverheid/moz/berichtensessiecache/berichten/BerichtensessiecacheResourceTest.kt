package nl.rijksoverheid.moz.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class BerichtensessiecacheResourceTest {

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
    fun `GET berichten zonder ontvanger retourneert 400`() {
        given()
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
    }

    @Test
    fun `GET berichten retourneert 409 als ophalen niet is aangeroepen`() {
        given()
            .header("X-Ontvanger", "onbekend-${System.nanoTime()}")
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
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("status", `is`(409))
            .body("detail", containsString("momenteel opgehaald"))
    }

    @Test
    fun `GET berichten retourneert 500 als ophalen is mislukt`() {
        val ontvanger = "fout-test-${System.nanoTime()}"
        val key = BerichtenCache.cacheKey(ontvanger)

        berichtenCache.storeAggregationStatus(key, AggregationStatus(status = OphalenStatus.FOUT, totaalMagazijnen = 2, mislukt = 2))
            .await().indefinitely()

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(500)
            .contentType("application/problem+json")
            .body("status", `is`(500))
            .body("detail", containsString("mislukt"))
    }

    @Test
    fun `GET zoeken retourneert 500 als ophalen is mislukt`() {
        val ontvanger = "fout-zoek-${System.nanoTime()}"
        val key = BerichtenCache.cacheKey(ontvanger)

        berichtenCache.storeAggregationStatus(key, AggregationStatus(status = OphalenStatus.FOUT, totaalMagazijnen = 2, mislukt = 2))
            .await().indefinitely()

        given()
            .queryParam("q", "test")
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(500)
            .contentType("application/problem+json")
            .body("status", `is`(500))
            .body("detail", containsString("mislukt"))
    }

    @Test
    fun `GET berichten retourneert gepagineerde resultaten na ophalen`() {
        given()
            .header("X-Ontvanger", "test-paginering")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .header("X-Ontvanger", "test-paginering")
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
            .body("_links.first.href", notNullValue())
            .body("_links.last.href", notNullValue())
            .body("_links.next.href", containsString("page=1"))
    }

    @Test
    fun `GET berichten paginering page 1 geeft andere resultaten`() {
        given()
            .header("X-Ontvanger", "test-page1")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .header("X-Ontvanger", "test-page1")
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
            .`when`().get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
    }

    @Test
    fun `GET zoeken met te korte query retourneert 400 als problem json`() {
        given()
            .queryParam("q", "a")
            .`when`().get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
    }

    @Test
    fun `GET bericht by id retourneert bericht uit cache met correcte velden`() {
        // Eerst ophalen zodat berichten in cache komen
        given()
            .header("X-Ontvanger", "byid-test-${System.nanoTime()}")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

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
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .queryParam("q", "bericht 1")
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(200)
            .body("berichten.size()", `is`(1))
            .body("totalElements", `is`(1))
    }

    @Test
    fun `GET zoeken op afzender retourneert gefilterde resultaten`() {
        val ontvanger = "zoek-afzender-${System.nanoTime()}"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        // Zoek op afzender OIN van magazijn-b bericht
        given()
            .queryParam("q", "00000005555555550000")
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(200)
            .body("berichten.size()", `is`(1))
            .body("berichten[0].magazijnId", `is`("magazijn-b"))
    }

    @Test
    fun `GET zoeken retourneert 409 als ophalen niet is aangeroepen`() {
        given()
            .queryParam("q", "test")
            .header("X-Ontvanger", "onbekend-zoek-${System.nanoTime()}")
            .`when`().get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("status", `is`(409))
            .body("detail", containsString("nog niet opgehaald"))
    }

    @Test
    fun `GET berichten paginering buiten bereik retourneert lege lijst`() {
        val ontvanger = "page-range-${System.nanoTime()}"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .header("X-Ontvanger", ontvanger)
            .queryParam("page", 999)
            .queryParam("pageSize", 2)
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten.size()", `is`(0))
            .body("totalElements", `is`(4))
            .body("totalPages", `is`(2))
    }

    @Test
    fun `GET bericht by id retourneert 404 als bericht niet in cache zit`() {
        given()
            .`when`().get("/api/v1/berichten/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", `is`(404))
    }
}
