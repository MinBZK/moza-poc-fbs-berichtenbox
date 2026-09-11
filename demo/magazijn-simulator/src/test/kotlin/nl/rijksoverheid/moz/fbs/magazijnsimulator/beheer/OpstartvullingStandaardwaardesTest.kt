package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test

/**
 * De configuratievorm die daadwerkelijk uitrolt: alleen ontvangers.
 *
 * Het generatiescript schrijft de aantallen bewust niet mee — die laat het aan de simulator. Zetten
 * alle tests ze wél, dan is de standaardwaarde de enige regel die in productie gebruikt wordt en
 * nergens getoetst, en dan valt een verkeerde default pas op in een demo.
 */
@QuarkusTest
@TestProfile(OpstartvullingStandaardwaardesTest.AlleenOntvangers::class)
class OpstartvullingStandaardwaardesTest {

    class AlleenOntvangers : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("magazijnsimulator.opstartvulling.ontvangers" to ONTVANGER)
    }

    @Test
    fun `zonder aantallen gelden de standaardwaardes van het beheerpad`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/$MAGAZIJN/api/v1/berichten?pageSize=$RUIM_BOVEN_DE_STANDAARD")
            .then()
            .statusCode(200)
            .body("berichten", hasSize<Any>(SeedVerzoek.STANDAARD_AANTAL))
            .body(
                "berichten.findAll { it.bijlagen.size() > 0 }",
                hasSize<Any>(SeedVerzoek.STANDAARD_AANTAL / SeedVerzoek.STANDAARD_BIJLAGE_ELKE),
            )
    }

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000080"
        const val MAGAZIJN = "00000009000000000001"

        /** De lijst geeft er standaard twintig; de vulling zet er evenveel, dus vraag er ruim meer op. */
        const val RUIM_BOVEN_DE_STANDAARD = 100
    }
}
