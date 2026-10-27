package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * De koppeling tussen verzoek en quotum achter een vertrouwde proxy: de test zelf is die proxy
 * (127.0.0.1). Een client die een eigen `X-Forwarded-For`-regel meestuurt, kiest daarmee niet zijn
 * quotum; de regel die de proxy erachter zet, telt.
 */
@QuarkusTest
@TestProfile(VertrouwdeProxyTest.Profiel::class)
class VertrouwdeProxyTest {

    class Profiel : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("simulator.rechtstreeks.vertrouwde-proxies" to "127.0.0.1/32")
    }

    @Test
    fun `een verzonnen X-Forwarded-For per verzoek omzeilt het quotum niet`() {
        val statussen = (1..MAX_CHALLENGES_PER_CLIENT + 1).map { i ->
            given()
                .header("X-Forwarded-For", "6.6.6.$i")
                .header("X-Forwarded-For", ECHT_ADRES)
                .post("/magazijn/$MAGAZIJN/api/v1/rechtstreeks/challenge")
                .then().extract().statusCode()
        }

        assertEquals(List(MAX_CHALLENGES_PER_CLIENT) { 200 } + 503, statussen)

        // Een ander echt adres achter dezelfde proxy heeft zijn eigen quotum.
        given().header("X-Forwarded-For", "198.51.100.8").post("/magazijn/$MAGAZIJN/api/v1/rechtstreeks/challenge")
            .then().statusCode(200)
    }

    private companion object {
        const val MAGAZIJN = "00000009000000000001"
        const val ECHT_ADRES = "198.51.100.7"
        const val MAX_CHALLENGES_PER_CLIENT = 20
    }
}
