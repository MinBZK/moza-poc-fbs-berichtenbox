package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * De enige test met de planner aan. Overal elders staat hij uit en roept de test het verversen
 * zelf aan; daarmee is nergens gedekt dat de planner de sleutel `stelseldocument.verversen` ook
 * werkelijk leest. Valt die koppeling weg, dan merkt niemand het tot het document na 24 uur
 * verloopt en de dienst 503 geeft.
 */
@QuarkusTest
@TestProfile(VerversenPlannerTest.PlannerAan::class)
class VerversenPlannerTest {

    class PlannerAan : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "stelseldocument.verversen" to "PT1S",
            "stelseldocument.geldigheid" to "PT1M",
        )
    }

    private fun payload() = Afnemer.payload(given().get("/api/v1/stelseldocument").then().statusCode(200).extract().asString())

    @Test
    @Timeout(20)
    fun `de planner geeft op het ingestelde interval een nieuw exemplaar uit`() {
        val eerste = payload()
        var laatste = eerste

        while (laatste.path("iat").asLong() <= eerste.path("iat").asLong()) {
            Thread.sleep(250)
            laatste = payload()
        }

        assertTrue(laatste.path("iat").asLong() > eerste.path("iat").asLong())
        assertEquals(eerste.path("version").asText(), laatste.path("version").asText())
        assertEquals(60L, laatste.path("exp").asLong() - laatste.path("iat").asLong())
    }
}
