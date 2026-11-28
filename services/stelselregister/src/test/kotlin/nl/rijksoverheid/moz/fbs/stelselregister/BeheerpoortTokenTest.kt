package nl.rijksoverheid.moz.fbs.stelselregister

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.emptyString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URL

/**
 * Met een token geconfigureerd laat de beheerpoort alleen dat token door. De regel zelf (ook
 * "zonder token buiten dev en test dicht") toetst `BeheerpoortToegangTest`; hier staat dat de
 * dienst hem werkelijk vóór elke route op de beheerpoort zet, en niet op de applicatiepoort.
 */
@QuarkusTest
@TestProfile(BeheerpoortTokenTest.MetToken::class)
class BeheerpoortTokenTest {

    // Relatief aan het beheer-root-pad (`/q`), dus `/q/metrics`.
    @TestHTTPResource(value = "/metrics", management = true)
    lateinit var meetgegevens: URL

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "Bearer niet-het-token", "Bearer ", "Basic $TOKEN", TOKEN])
    fun `zonder geldig token geeft de beheerpoort een kale 401`(authorization: String?) {
        val verzoek = given()

        if (authorization != null) verzoek.header("Authorization", authorization)

        verzoek.`when`().get(meetgegevens).then()
            .statusCode(401)
            .header("WWW-Authenticate", equalTo("Bearer"))
            .body(emptyString())
    }

    @Test
    fun `met het token levert de beheerpoort de meetgegevens`() {
        given().header("Authorization", "Bearer $TOKEN")
            .`when`().get(meetgegevens).then()
            .statusCode(200)
            // Een JVM-meter: die bestaat vanaf de start, ook vóór het eerste HTTP-verzoek.
            .body(containsString("# TYPE jvm_memory_used_bytes gauge"))
    }

    // Een 401 in plaats van een 404: ook een pad dat later op de beheerpoort bijkomt, valt achter het token.
    @Test
    fun `ook een onbekend pad op de beheerpoort vraagt het token`() {
        given().`when`().get(meetgegevens.toURI().resolve("bestaat-niet").toURL()).then().statusCode(401)

        given().header("Authorization", "Bearer $TOKEN")
            .`when`().get(meetgegevens.toURI().resolve("bestaat-niet").toURL()).then().statusCode(404)
    }

    @Test
    fun `de applicatiepoort vraagt geen token`() {
        given().`when`().get("/q/health/live").then().statusCode(200)
    }

    class MetToken : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("fbs.beheerpoort.token" to TOKEN)
    }

    private companion object {
        const val TOKEN = "test-beheertoken-3f9c"
    }
}
