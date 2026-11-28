package nl.rijksoverheid.moz.fbs.beheerconsole

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Wat de dienst publiceert, is de geschreven spec: niet het skelet dat SmallRye uit de code afleidt,
 * want dat mist servers, contact, headers en foutresponses. Gelijk als boom, dus volgorde telt niet.
 */
@QuarkusTest
class GepubliceerdeSpecTest {

    @Test
    fun `het gepubliceerde document is de geschreven spec`() {
        val geschreven = ObjectMapper(YAMLFactory()).readTree(javaClass.getResource("/openapi/beheerconsole-api.yaml"))
        val gepubliceerd = ObjectMapper().readTree(given().`when`().get("/openapi.json").then().statusCode(200).extract().asString())

        assertEquals(geschreven, gepubliceerd)
    }
}
