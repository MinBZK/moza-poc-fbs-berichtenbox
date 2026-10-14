package nl.rijksoverheid.moz.fbs.berichtenmagazijn.specificatie

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ApiInfo
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasKey
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
        OpenApiInteractionValidator.createForSpecificationUrl("openapi/berichtenmagazijn-api.yaml").build(),
    )

    private fun body(pad: String): String =
        given().`when`().get(pad).then().statusCode(200).extract().asString()

    // Zonder token of X-Ontvanger: afnemers en tooling halen het document anoniem op.
    @Test
    fun `het document onder het basisadres voldoet aan de spec`() {
        given()
            .filter(validator)
            // Zoals een browser op een ander domein vraagt; zonder Origin bewijst de test alleen
            // dat de resource de header zet, niet dat een CORS-laag ervoor hem laat staan.
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
            .body("paths", hasKey("/api/v1/aanleveringen"))
            .body("paths", hasKey("/api/v1/openapi.json"))
    }

    // Het document opent CORS voor zichzelf, niet voor de berichten. CORS staat hier expliciet
    // uit; deze test borgt dat het document daar geen gat in slaat.
    @Test
    fun `de rest van de API blijft dicht voor andere origins`() {
        val header = given()
            .header("Origin", "https://elders.example")
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .extract()
            .header("Access-Control-Allow-Origin")

        assertNull(header)
    }
}
