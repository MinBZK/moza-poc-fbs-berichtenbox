package nl.rijksoverheid.moz.berichtensessiecache.berichten

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class OpenApiContractTest {

    @Inject
    lateinit var berichtenCache: BerichtenCache

    // Valideert zowel request als response tegen de spec.
    // Null-waarden voor niet-verplichte properties (bijv. _links/inhoud, Problem/instance)
    // worden getolereerd via WARN-level.
    private val validationFilter = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtensessiecache-api.yaml")
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.response.body.schema.type", ValidationReport.Level.WARN)
                    .build()
            )
            .build()
    )

    // Valideert alleen de response, niet de request. Voor tests die bewust een
    // ongeldige request sturen (ontbrekende verplichte header, ongeldige parameters).
    private val responseOnlyFilter = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtensessiecache-api.yaml")
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.response.body.schema.type", ValidationReport.Level.WARN)
                    .withLevel("validation.request.parameter.header.missing", ValidationReport.Level.IGNORE)
                    .withLevel("validation.request.parameter.query.missing", ValidationReport.Level.IGNORE)
                    .withLevel("validation.request.body.missing", ValidationReport.Level.IGNORE)
                    .build()
            )
            .build()
    )

    private val ontvanger = "999993653"

    @BeforeEach
    fun setUp() {
        MockMagazijnClientFactory.shouldFailA = false
        MockMagazijnClientFactory.shouldFailB = false
        MockMagazijnClientFactory.shouldTimeoutA = false
        MockMagazijnClientFactory.shouldTimeoutB = false
        (berichtenCache as MockBerichtenCache).clear()
    }

    private fun ophalenBerichten() {
        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)
            .extract().body().asString()
    }

    // ── Happy paths ──────────────────────────────────────────────────────

    @Test
    fun `GET berichten response conform BerichtensessiecacheResponse schema`() {
        ophalenBerichten()

        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten")
            .then().statusCode(200)
    }

    @Test
    fun `GET berichten met paginering conform schema`() {
        ophalenBerichten()

        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .queryParam("page", 0)
            .queryParam("pageSize", 2)
            .`when`().get("/api/v1/berichten")
            .then().statusCode(200)
    }

    @Test
    fun `GET berichten met afzender filter conform schema`() {
        ophalenBerichten()

        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .queryParam("afzender", "00000001234567890000")
            .`when`().get("/api/v1/berichten")
            .then().statusCode(200)
    }

    @Test
    fun `GET bericht bij ID conform BerichtResponse schema`() {
        ophalenBerichten()

        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then().statusCode(200)
    }

    @Test
    fun `POST bericht conform BerichtResponse schema met 201`() {
        ophalenBerichten()

        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .contentType(ContentType.JSON)
            .body("""
                {
                    "berichtId": "55555555-5555-5555-5555-555555555555",
                    "afzender": "00000001234567890000",
                    "ontvanger": "$ontvanger",
                    "onderwerp": "Contract test bericht",
                    "tijdstip": "2026-03-10T14:00:00Z",
                    "magazijnId": "magazijn-a"
                }
            """.trimIndent())
            .`when`().post("/api/v1/berichten")
            .then().statusCode(201)
    }

    @Test
    fun `PATCH bericht status conform BerichtResponse schema`() {
        ophalenBerichten()

        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{"status": "gelezen"}""")
            .`when`().patch("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then().statusCode(200)
    }

    @Test
    fun `GET zoeken conform BerichtensessiecacheResponse schema`() {
        ophalenBerichten()

        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .queryParam("q", "bericht")
            .`when`().get("/api/v1/berichten/_zoeken")
            .then().statusCode(200)
    }

    // ── 400 Bad Request ─────────────────────────────────────────────────

    @Test
    fun `GET berichten zonder ontvanger retourneert 400 conform Problem schema`() {
        given()
            .filter(responseOnlyFilter)
            .`when`().get("/api/v1/berichten")
            .then().statusCode(400)
    }

    @Test
    fun `GET bericht bij ID zonder ontvanger retourneert 400 conform Problem schema`() {
        given()
            .filter(responseOnlyFilter)
            .`when`().get("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then().statusCode(400)
    }

    @Test
    fun `GET zoeken zonder ontvanger retourneert 400 conform Problem schema`() {
        given()
            .filter(responseOnlyFilter)
            .queryParam("q", "test")
            .`when`().get("/api/v1/berichten/_zoeken")
            .then().statusCode(400)
    }

    // ── 404 Not Found ───────────────────────────────────────────────────

    @Test
    fun `GET bericht bij onbekend ID retourneert 404 conform Problem schema`() {
        ophalenBerichten()

        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/99999999-9999-9999-9999-999999999999")
            .then().statusCode(404)
    }

    @Test
    fun `PATCH onbekend bericht retourneert 404 conform Problem schema`() {
        ophalenBerichten()

        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .contentType("application/merge-patch+json")
            .body("""{"status": "gelezen"}""")
            .`when`().patch("/api/v1/berichten/99999999-9999-9999-9999-999999999999")
            .then().statusCode(404)
    }

    @Test
    fun `POST bericht zonder actieve sessie retourneert 404 conform Problem schema`() {
        // Geen ophalen gedaan → geen actieve sessie
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .contentType(ContentType.JSON)
            .body("""
                {
                    "berichtId": "55555555-5555-5555-5555-555555555555",
                    "afzender": "00000001234567890000",
                    "ontvanger": "$ontvanger",
                    "onderwerp": "Test",
                    "tijdstip": "2026-03-10T14:00:00Z",
                    "magazijnId": "magazijn-a"
                }
            """.trimIndent())
            .`when`().post("/api/v1/berichten")
            .then().statusCode(404)
    }

    // ── 409 Conflict ────────────────────────────────────────────────────

    @Test
    fun `GET berichten zonder ophalen retourneert 409 conform Problem schema`() {
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten")
            .then().statusCode(409)
    }

    @Test
    fun `GET bericht bij ID zonder ophalen retourneert 409 conform Problem schema`() {
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/11111111-1111-1111-1111-111111111111")
            .then().statusCode(409)
    }

    @Test
    fun `GET zoeken zonder ophalen retourneert 409 conform Problem schema`() {
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .queryParam("q", "test")
            .`when`().get("/api/v1/berichten/_zoeken")
            .then().statusCode(409)
    }

    @Test
    fun `GET berichten tijdens ophalen retourneert 409 conform Problem schema`() {
        (berichtenCache as MockBerichtenCache).simuleerBezig(BerichtenCache.cacheKey(ontvanger))

        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten")
            .then().statusCode(409)
    }

    // ── 500 Internal Server Error ───────────────────────────────────────

    @Test
    fun `GET berichten na mislukt ophalen retourneert 500 conform Problem schema`() {
        // Simuleer een mislukt ophalen door status FOUT in de cache te zetten
        val key = BerichtenCache.cacheKey(ontvanger)
        val foutStatus = AggregationStatus(
            status = OphalenStatus.FOUT,
            totaalMagazijnen = 2,
            geslaagd = 0,
            mislukt = 2,
        )
        berichtenCache.storeAggregationStatus(key, foutStatus).await().indefinitely()

        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten")
            .then().statusCode(500)
    }
}
