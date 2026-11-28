package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Een 405 noemt de methodes die wel mogen (RFC 9110 §15.5.6), in de vaste foutvorm van de keten. */
@QuarkusTest
class MethodeNietToegestaanTest {

    @ParameterizedTest
    @ValueSource(strings = ["GET", "PUT", "DELETE", "PATCH"])
    fun `een niet-toegestane methode noemt wat wel mag`(methode: String) {
        given()
            .`when`()
            .request(methode, "/api/v1/aanleveringen")
            .then()
            .statusCode(405)
            .contentType("application/problem+json")
            .header("Allow", containsString("POST"))
            .header("Allow", not(containsString(methode)))
    }

    // Een pad met een parameter, exact: zo valt ook een verkeerd template of een ontbrekende HEAD op.
    @org.junit.jupiter.api.Test
    fun `een pad met een parameter noemt precies zijn methodes`() {
        given()
            .`when`()
            .put("/api/v1/berichten/5f0c3e4a-0000-4000-8000-000000000001")
            .then()
            .statusCode(405)
            .header("Allow", org.hamcrest.Matchers.equalTo("DELETE, GET, HEAD, OPTIONS, PATCH"))
    }
}
