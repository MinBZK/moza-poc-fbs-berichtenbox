package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
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
            .body("detail", containsString("must not be null"))
            .body("detail", not(containsString("getBerichten.")))
    }

    @Test
    fun `GET berichten met lege ontvanger retourneert 400`() {
        given()
            .header("X-Ontvanger", "")
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
            .body("detail", notNullValue())
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
            .header("API-Version", `is`("v1"))
            .body("berichten.size()", `is`(2))
            .body("page", `is`(0))
            .body("pageSize", `is`(2))
            .body("totalElements", `is`(4))
            .body("totalPages", `is`(2))
            .body("_aggregatie.status", `is`("GEREED"))
            .body("_links.self.href", containsString("page=0"))
            .body("_links.self.href", not(containsString("ontvanger")))
            .body("_links.next.href", not(containsString("ontvanger")))
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
    fun `GET bericht by id zonder ontvanger retourneert 400`() {
        given()
            .`when`().get("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
            .body("detail", containsString("must not be null"))
            .body("detail", not(containsString("getBerichtById.")))
    }

    @Test
    fun `GET bericht by id retourneert 409 als ophalen niet is aangeroepen`() {
        given()
            .header("X-Ontvanger", "onbekend-byid-${System.nanoTime()}")
            .`when`().get("/api/v1/berichten/00000000-0000-0000-0000-000000000000")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("status", `is`(409))
            .body("detail", containsString("nog niet opgehaald"))
    }

    @Test
    fun `GET bericht by id retourneert 409 als ophalen nog bezig is`() {
        val ontvanger = "bezig-byid-${System.nanoTime()}"
        val key = BerichtenCache.cacheKey(ontvanger)

        berichtenCache.storeAggregationStatus(key, AggregationStatus(status = OphalenStatus.BEZIG, totaalMagazijnen = 1))
            .await().indefinitely()

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/00000000-0000-0000-0000-000000000000")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("status", `is`(409))
            .body("detail", containsString("momenteel opgehaald"))
    }

    @Test
    fun `GET bericht by id retourneert 500 als ophalen is mislukt`() {
        val ontvanger = "fout-byid-${System.nanoTime()}"
        val key = BerichtenCache.cacheKey(ontvanger)

        berichtenCache.storeAggregationStatus(key, AggregationStatus(status = OphalenStatus.FOUT, totaalMagazijnen = 2, mislukt = 2))
            .await().indefinitely()

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/00000000-0000-0000-0000-000000000000")
            .then()
            .statusCode(500)
            .contentType("application/problem+json")
            .body("status", `is`(500))
            .body("detail", containsString("mislukt"))
    }

    @Test
    fun `GET zoeken zonder ontvanger retourneert 400`() {
        given()
            .queryParam("q", "test")
            .`when`().get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
            .body("detail", containsString("must not be null"))
            .body("detail", not(containsString("zoekBerichten.")))
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
        val ontvanger = "999993653"

        // Eerst ophalen zodat berichten in cache komen
        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .header("X-Ontvanger", ontvanger)
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
    fun `GET bericht by id retourneert 404 als ontvanger niet overeenkomt`() {
        val eigenOntvanger = "andere-ontvanger-${System.nanoTime()}"

        // Ophalen zodat aggregation status GEREED is
        given()
            .header("X-Ontvanger", eigenOntvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        // Bericht bestaat in cache maar hoort bij ontvanger "999993653", niet bij eigenOntvanger
        given()
            .header("X-Ontvanger", eigenOntvanger)
            .`when`().get("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", `is`(404))
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
    fun `GET zoeken met afzender filter retourneert gefilterde resultaten`() {
        val ontvanger = "zoek-afzender-${System.nanoTime()}"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        // Filter op afzender OIN van magazijn-b bericht via afzender parameter
        given()
            .queryParam("q", "Test")
            .queryParam("afzender", "00000005555555550000")
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
    fun `GET berichten met afzender filter retourneert alleen berichten van die afzender`() {
        val ontvanger = "afzender-filter-${System.nanoTime()}"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        // Filter op afzender van magazijn-b bericht
        given()
            .header("X-Ontvanger", ontvanger)
            .queryParam("afzender", "00000005555555550000")
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten.size()", `is`(1))
            .body("berichten[0].magazijnId", `is`("magazijn-b"))
    }

    @Test
    fun `GET berichten zonder afzender filter retourneert alle berichten`() {
        val ontvanger = "geen-afzender-filter-${System.nanoTime()}"

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
            .body("berichten.size()", `is`(4))
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

    // --- PATCH /berichten/{berichtId} ---

    @ParameterizedTest(name = "PATCH status={0} → {1}")
    @CsvSource(
        "gelezen,   200",
        "ongelezen, 200",
        "bekeken,   400",
    )
    fun `PATCH bericht status validatie`(status: String, expectedHttpStatus: Int) {
        val ontvanger = "999993653"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        val patchSpec = given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{"status": "$status"}""")
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(expectedHttpStatus)

        if (expectedHttpStatus == 200) {
            patchSpec
                .body("berichtId", `is`("11111111-1111-1111-1111-111111111111"))
                .body("status", `is`(status))

            // Na de PATCH moet een volgende GET de nieuwe status teruggeven
            given()
                .header("X-Ontvanger", ontvanger)
                .`when`().get("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
                .then()
                .statusCode(200)
                .body("status", `is`(status))
        } else {
            // Ongeldige enum-waarde komt door Jackson's `BerichtStatus.fromValue(...)`
            // niet door als typed enum, maar als `null`. Bean Validation `@NotNull` op
            // `BerichtStatusUpdate.status` triggert dan een 400 Problem+JSON via de
            // ConstraintViolationExceptionMapper. Voor pure malformed JSON zie de
            // aparte test "PATCH malformed JSON-body".
            patchSpec
                .contentType("application/problem+json")
                .body("status", `is`(400))
        }
    }

    @Test
    fun `PATCH malformed JSON-body retourneert 400 problem+json`() {
        val ontvanger = "999993653"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)

        // Ongeldige JSON syntax → JsonProcessingException → JsonProcessingExceptionMapper
        given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{"status": """) // afgekapt, parse-fout
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
            .body("title", `is`("Bad Request"))
    }

    @Test
    fun `PATCH bericht niet gevonden retourneert 404`() {
        val ontvanger = "patch-404-${System.nanoTime()}"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{"status": "gelezen"}""")
            .`when`().patch("/api/v1/berichten/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
    }

    @Test
    fun `PATCH bericht ontvanger mismatch retourneert 404`() {
        val eigenOntvanger = "andere-patch-${System.nanoTime()}"

        given()
            .header("X-Ontvanger", eigenOntvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        // Bericht hoort bij "999993653", niet bij eigenOntvanger
        given()
            .header("X-Ontvanger", eigenOntvanger)
            .contentType("application/merge-patch+json")
            .body("""{"status": "gelezen"}""")
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
    }

    @Test
    fun `PATCH bericht zonder ontvanger retourneert 400`() {
        given()
            .contentType("application/merge-patch+json")
            .body("""{"status": "gelezen"}""")
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    // --- POST /berichten ---

    @Test
    fun `POST bericht toevoegen retourneert 201`() {
        val ontvanger = "post-201-${System.nanoTime()}"

        // Aanmeld Service mag alleen actieve sessies bijwerken: zorg eerst voor aggregatie.
        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/json")
            .body("""
                {
                    "berichtId": "55555555-5555-5555-5555-555555555555",
                    "afzender": "00000001234567890000",
                    "ontvanger": "$ontvanger",
                    "onderwerp": "Nieuw bericht",
                    "tijdstip": "2026-03-10T14:00:00Z",
                    "magazijnId": "magazijn-a"
                }
            """.trimIndent())
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
            .body("berichtId", `is`("55555555-5555-5555-5555-555555555555"))
            .body("onderwerp", `is`("Nieuw bericht"))
    }

    @Test
    fun `POST bericht zonder actieve sessie retourneert 404`() {
        val ontvanger = "post-zonder-sessie-${System.nanoTime()}"

        given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/json")
            .body("""
                {
                    "berichtId": "66666666-6666-6666-6666-666666666666",
                    "afzender": "00000001234567890000",
                    "ontvanger": "$ontvanger",
                    "onderwerp": "Nieuw bericht",
                    "tijdstip": "2026-03-10T14:00:00Z",
                    "magazijnId": "magazijn-a"
                }
            """.trimIndent())
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("detail", containsString("actieve sessie"))
    }

    @Test
    fun `POST bericht met afwijkende ontvanger retourneert 400`() {
        given()
            .header("X-Ontvanger", "andere-ontvanger")
            .contentType("application/json")
            .body("""
                {
                    "berichtId": "55555555-5555-5555-5555-555555555555",
                    "afzender": "00000001234567890000",
                    "ontvanger": "999993653",
                    "onderwerp": "Nieuw bericht",
                    "tijdstip": "2026-03-10T14:00:00Z",
                    "magazijnId": "magazijn-a"
                }
            """.trimIndent())
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("detail", containsString("komt niet overeen"))
    }

    @Test
    fun `POST bericht zonder ontvanger retourneert 400`() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "berichtId": "55555555-5555-5555-5555-555555555555",
                    "afzender": "00000001234567890000",
                    "ontvanger": "999993653",
                    "onderwerp": "Nieuw bericht",
                    "tijdstip": "2026-03-10T14:00:00Z",
                    "magazijnId": "magazijn-a"
                }
            """.trimIndent())
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET bericht by id retourneert 404 als bericht niet in cache zit`() {
        val ontvanger = "byid-404-${System.nanoTime()}"

        // Ophalen zodat aggregation status GEREED is
        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", `is`(404))
    }
}
