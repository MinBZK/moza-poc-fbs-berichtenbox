package nl.rijksoverheid.moz.fbs.stelselregister.specificatie

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import nl.rijksoverheid.moz.fbs.stelselregister.ApiInfo
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Het OpenAPI-document onder het basisadres: vrij op te halen en gelijk aan dat op de root. */
@QuarkusTest
class SpecificatieResourceTest {

    private val validator = OpenApiValidationFilter(
        OpenApiInteractionValidator.createForSpecificationUrl("openapi/stelselregister-api.yaml").build(),
    )

    private fun body(pad: String): String =
        given().`when`().get(pad).then().statusCode(200).extract().asString()

    // Zoals een browser op een ander domein vraagt: de CORS-configuratie van de dienst zet de header.
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
            .header("Access-Control-Allow-Origin", "https://elders.example")
    }

    // Zonder Origin is er geen CORS-verzoek, en dan hoort de header er ook niet te staan.
    @Test
    fun `zonder Origin geen CORS-header`() {
        val header = given().`when`().get("/api/v1/openapi.json").then().statusCode(200).extract()
            .header("Access-Control-Allow-Origin")

        assertNull(header)
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
            .body("paths", hasKey("/api/v1/stelseldocument"))
            .body("paths", hasKey("/api/v1/openapi.json"))
    }

    // Het pad staat in de spec als alleen GET; een resource geeft dan 405, geen 404.
    @Test
    fun `een andere methode dan GET geeft 405`() {
        given().`when`().post("/api/v1/openapi.json").then().statusCode(405)
    }
}
