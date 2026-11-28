package nl.rijksoverheid.moz.fbs.beheerconsole.specificatie

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import nl.rijksoverheid.moz.fbs.beheerconsole.ApiInfo
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Het OpenAPI-document onder het basisadres: vrij op te halen, gelijk aan dat op de root en als
 * enige pad open voor andere origins.
 */
@QuarkusTest
class SpecificatieResourceTest {

    private val validator = OpenApiValidationFilter(
        OpenApiInteractionValidator.createForSpecificationUrl("openapi/beheerconsole-api.yaml").build(),
    )

    // Een Accept die de spec niet kent, is zelf al een afwijking; hier telt alleen het antwoord.
    private val alleenAntwoord = OpenApiValidationFilter(
        OpenApiInteractionValidator.createForSpecificationUrl("openapi/beheerconsole-api.yaml")
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.request.accept.notAllowed", ValidationReport.Level.IGNORE)
                    .build(),
            )
            .build(),
    )

    private fun body(pad: String): String =
        given().`when`().get(pad).then().statusCode(200).extract().asString()

    // Zoals een browser op een ander domein vraagt; zonder Origin bewijst de test alleen dat de
    // resource de header zet, niet dat een CORS-laag ervoor hem laat staan.
    @Test
    fun `het document onder het basisadres voldoet aan de spec`() {
        given()
            .filter(validator)
            .header("Origin", "https://elders.example")
            .`when`()
            .get("/api/v1/openapi.json")
            .then()
            .statusCode(200)
            .contentType(startsWith("application/json"))
            .header("API-Version", ApiInfo.SPEC_VERSION)
            .header("Access-Control-Allow-Origin", "*")
    }

    // De bron is een interne bean van Quarkus die bij een onbekende documentnaam stil een leeg
    // document geeft. De root leest via Quarkus' eigen handler, dus een verschil valt hier op.
    @Test
    fun `het document onder het basisadres is hetzelfde als dat op de root`() {
        assertEquals(body("/openapi.json"), body("/api/v1/openapi.json"))
    }

    @Test
    fun `het document beschrijft de API, inclusief zijn eigen pad`() {
        given()
            .`when`()
            .get("/api/v1/openapi.json")
            .then()
            .statusCode(200)
            .body("info.version", equalTo(ApiInfo.SPEC_VERSION))
            .body("servers[0].url", equalTo("/api/v1"))
            .body("paths", hasKey("/stelsel"))
            .body("paths", hasKey("/openapi.json"))
    }

    @Test
    fun `een Accept zonder JSON geeft een 406 volgens het foutcontract`() {
        given().filter(alleenAntwoord).accept("application/yaml").`when`().get("/api/v1/openapi.json").then().statusCode(406)
    }

    // Het pad staat in de spec als alleen GET; een resource geeft dan 405, geen 404.
    @Test
    fun `een andere methode dan GET geeft 405`() {
        given().`when`().post("/api/v1/openapi.json").then().statusCode(405)
    }

    // Het document opent CORS voor zichzelf, niet voor het overzicht van het stelsel.
    @Test
    fun `de rest van de API blijft dicht voor andere origins`() {
        val header = given()
            .header("Origin", "https://elders.example")
            .`when`()
            .get("/api/v1/stelsel")
            .then()
            // Welke fout het overzicht hier geeft, hangt aan het testprofiel; een 404 betekent een
            // verkeerd pad, en dan zegt de toets niets.
            .statusCode(not(equalTo(404)))
            .extract()
            .header("Access-Control-Allow-Origin")

        assertNull(header)
    }
}
