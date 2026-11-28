package nl.rijksoverheid.moz.fbs.stelselregister

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test
import java.net.URL

/**
 * Zonder token en buiten dev/test is de beheerpoort dicht. Een samengesteld profiel (`test,dicht`)
 * valt buiten de allowlist en geldt dus als "buiten dev/test", terwijl `%test` blijft gelden: zo is
 * het pad dat in productie loopt, in een test te zien. In het stelselregister, omdat dat de minste
 * transport-validators heeft om tevreden te stellen; de bedrading is in de drie diensten gelijk.
 */
@QuarkusTest
@TestProfile(BeheerpoortZonderTokenTest.Dicht::class)
class BeheerpoortZonderTokenTest {

    // Relatief aan het beheer-root-pad (`/q`), dus `/q/metrics`.
    @TestHTTPResource(value = "/metrics", management = true)
    lateinit var meetgegevens: URL

    @Test
    fun `zonder token weigert de beheerpoort ook een verzoek met een header`() {
        given().`when`().get(meetgegevens).then().statusCode(401)
        given().header("Authorization", "Bearer iets").`when`().get(meetgegevens).then().statusCode(401)
    }

    @Test
    fun `de applicatiepoort blijft bereikbaar`() {
        given().`when`().get("/q/health/live").then().statusCode(200)
    }

    class Dicht : QuarkusTestProfile {
        override fun getConfigProfile(): String = "test,dicht"

        // Wat de transport-validators buiten dev/test eisen; het register zelf doet hier niet ter zake.
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "magazijnen.\"00000000000000100000\".url" to "https://localhost:8090",
            "magazijnen.\"00000001823288444000\".url" to "https://localhost:8091",
            "fbs.http.tls.termination" to "mesh",
        )
    }
}
