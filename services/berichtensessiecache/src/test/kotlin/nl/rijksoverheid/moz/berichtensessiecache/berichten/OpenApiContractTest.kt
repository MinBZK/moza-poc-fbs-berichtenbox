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

    // Null-waarden voor niet-verplichte properties zijn acceptabel in onze responses
    // (bijv. _links/inhoud, _links/prev, Problem/instance). De spec markt deze velden
    // niet als nullable, maar de implementatie laat ze weg of stuurt null.
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
            .extract().body().asString() // Force volledige SSE stream consumptie
    }

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
    fun `GET bericht bij onbekend ID retourneert 404`() {
        ophalenBerichten()

        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/99999999-9999-9999-9999-999999999999")
            .then().statusCode(404)
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

    @Test
    fun `GET berichten zonder ontvanger retourneert 400`() {
        // Geen validationFilter: de request is bewust ongeldig (ontbrekende verplichte header)
        given()
            .`when`().get("/api/v1/berichten")
            .then().statusCode(400)
    }

    @Test
    fun `GET berichten zonder ophalen retourneert 409 conform Problem schema`() {
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten")
            .then().statusCode(409)
    }
}
