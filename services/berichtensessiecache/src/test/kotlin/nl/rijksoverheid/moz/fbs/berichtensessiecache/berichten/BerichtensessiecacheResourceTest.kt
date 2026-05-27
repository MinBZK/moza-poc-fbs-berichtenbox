package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
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
            .body("detail", containsString("errorId"))
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
            .body("detail", containsString("errorId"))
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
            .body("detail", containsString("errorId"))
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
        val ontvanger = "BSN:999993653"

        // Eerst ophalen zodat berichten in cache komen
        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        // Detail-respons bevat WEL inhoud (en bijlagen-veld als array — leeg bij aantalBijlagen=0).
        // Anders dan de lijst-respons; die splitsing is de invariant die we hier vastleggen.
        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(200)
            .body("berichtId", `is`("11111111-1111-1111-1111-111111111111"))
            .body("afzender", `is`("00000001234567890000"))
            .body("ontvanger", `is`("BSN:999993653"))
            .body("onderwerp", `is`("Test bericht 1"))
            .body("magazijnId", `is`("magazijn-a"))
            .body("aantalBijlagen", `is`(0))
            .body("inhoud", `is`("Inhoud van test bericht 1"))
            .body("bijlagen", notNullValue())
    }

    @Test
    fun `GET berichten lijst-respons bevat geen inhoud of bijlagen op samenvatting`() {
        val ontvanger = "BSN:999993653"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        // Lichte BerichtSamenvatting: cache heeft inhoud + bijlagen-IDs, maar de
        // publieke lijst-vorm exposeert ze niet — daarvoor is GET /berichten/{id}.
        // `quarkus.jackson.serialization-inclusion=non_null` zorgt dat afwezige
        // velden ook echt afwezig zijn in JSON (geen `null`).
        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten[0].berichtId", notNullValue())
            .body("berichten[0].onderwerp", notNullValue())
            .body("berichten[0].inhoud", nullValue())
            .body("berichten[0].bijlagen", nullValue())
            .body("berichten.find { it.aantalBijlagen > 0 }.inhoud", nullValue())
            .body("berichten.find { it.aantalBijlagen > 0 }.bijlagen", nullValue())
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

        // Bericht bestaat in cache maar hoort bij ontvanger "BSN:999993653", niet bij eigenOntvanger
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
        val ontvanger = "BSN:999993653"

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
            // niet door als typed enum, maar als `null`. Sinds Batch A is `status` optioneel
            // in de spec (i.v.m. map-PATCH); de resource vangt de "geen geldige status én
            // geen map"-combinatie expliciet af als 400 Problem+JSON. Voor pure malformed
            // JSON zie de aparte test "PATCH malformed JSON-body".
            patchSpec
                .contentType("application/problem+json")
                .body("status", `is`(400))
        }
    }

    @Test
    fun `PATCH malformed JSON-body retourneert 400 problem+json`() {
        val ontvanger = "BSN:999993653"

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

        // Bericht hoort bij "BSN:999993653", niet bij eigenOntvanger
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

    @Test
    fun `PATCH alleen status update wijzigt status laat map ongemoeid`() {
        val ontvanger = "BSN:999993653"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)

        // Zet eerst een map zodat we kunnen verifiëren dat een alleen-status-PATCH die niet wist
        given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{"map": "werk"}""")
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then().statusCode(200)
            .body("map", `is`("werk"))

        given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{"status": "gelezen"}""")
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(200)
            .body("status", `is`("gelezen"))
            .body("map", `is`("werk"))
    }

    @Test
    fun `PATCH alleen map update wijzigt map laat status ongemoeid`() {
        val ontvanger = "BSN:999993653"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)

        // Zet eerst een status zodat we kunnen verifiëren dat een alleen-map-PATCH die niet wist
        given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{"status": "gelezen"}""")
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then().statusCode(200)
            .body("status", `is`("gelezen"))

        given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{"map": "archief"}""")
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(200)
            .body("map", `is`("archief"))
            .body("status", `is`("gelezen"))
    }

    @Test
    fun `PATCH status en map gecombineerd wijzigt beide velden`() {
        val ontvanger = "BSN:999993653"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)

        given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{"status": "gelezen", "map": "archief"}""")
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(200)
            .body("status", `is`("gelezen"))
            .body("map", `is`("archief"))
    }

    @Test
    fun `PATCH met lege body retourneert 400`() {
        val ontvanger = "BSN:999993653"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)

        given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{}""")
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
    }

    @Test
    fun `PATCH met te lange map retourneert 400`() {
        // Bean Validation (@Size(max=64) op de DTO-getter, geactiveerd via @Valid in de
        // gegenereerde JAX-RS interface) vangt dit af vóór de resource-body draait.
        val ontvanger = "BSN:999993653"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)

        val teLang = "x".repeat(65)
        given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{"map": "$teLang"}""")
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
    }

    @Test
    fun `PATCH met lege map retourneert 400`() {
        // Bean Validation (@Size(min=1) op de DTO-getter) vangt dit af; zie ook
        // `PATCH met te lange map retourneert 400`.
        val ontvanger = "BSN:999993653"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)

        given()
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{"map": ""}""")
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
    }

    // --- DELETE /berichten/{berichtId} ---

    @Test
    fun `DELETE bestaand bericht retourneert 204 en daarna GET retourneert 404`() {
        val ontvanger = "BSN:999993653"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().delete("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then().statusCode(204)

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then().statusCode(404)
    }

    @Test
    fun `DELETE niet-bestaand bericht retourneert 204 idempotent`() {
        val ontvanger = "delete-onbekend-${System.nanoTime()}"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().delete("/api/v1/berichten/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
            .then().statusCode(204)
    }

    @Test
    fun `DELETE met andere ontvanger retourneert 204 en lekt niet`() {
        // Bericht 11111111 hoort bij ontvanger "BSN:999993653". Een DELETE met een andere
        // ontvanger moet idempotent 204 retourneren (geen 404 — anders zou een aanvaller
        // berichten-bestaan kunnen probe-en), én het bericht moet bewaard blijven.
        val anderOntvanger = "delete-mismatch-${System.nanoTime()}"

        given()
            .header("X-Ontvanger", anderOntvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)

        given()
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)

        given()
            .header("X-Ontvanger", anderOntvanger)
            .`when`().delete("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then().statusCode(204)

        // Bericht moet nog steeds te benaderen zijn voor de échte ontvanger
        given()
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then().statusCode(200)
    }

    @Test
    fun `DELETE zonder ontvanger retourneert 400`() {
        given()
            .`when`().delete("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `DELETE herhaald op zelfde bericht retourneert 204 idempotent`() {
        val ontvanger = "delete-herhaald-${System.nanoTime()}"

        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)

        val berichtId = "aaaaaaaa-1111-2222-3333-444444444444"

        // Twee opeenvolgende DELETE's: tweede is geen 404 maar 204 (idempotent)
        repeat(2) {
            given()
                .header("X-Ontvanger", ontvanger)
                .`when`().delete("/api/v1/berichten/$berichtId")
                .then().statusCode(204)
        }
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
                    "inhoud": "Inhoud nieuw bericht",
                    "publicatietijdstip": "2026-03-10T14:00:00Z",
                    "magazijnId": "magazijn-a",
                    "aantalBijlagen": 0
                }
            """.trimIndent())
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
            .body("berichtId", `is`("55555555-5555-5555-5555-555555555555"))
            .body("onderwerp", `is`("Nieuw bericht"))
            .body("aantalBijlagen", `is`(0))
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
                    "inhoud": "Inhoud zonder sessie",
                    "publicatietijdstip": "2026-03-10T14:00:00Z",
                    "magazijnId": "magazijn-a",
                    "aantalBijlagen": 0
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
                    "inhoud": "Inhoud",
                    "publicatietijdstip": "2026-03-10T14:00:00Z",
                    "magazijnId": "magazijn-a",
                    "aantalBijlagen": 0
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
                    "inhoud": "Inhoud",
                    "publicatietijdstip": "2026-03-10T14:00:00Z",
                    "magazijnId": "magazijn-a",
                    "aantalBijlagen": 0
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
