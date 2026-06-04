package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * End-to-end dekking voor [BijlageContentTypeFilter] (review T-H2). Drijft de filter via
 * [BijlageMimeTestResource], zodat de security-kritieke fail-closed-tak mee-telt voor
 * quarkus-jacoco — een regressie naar fail-open (de KDoc waarschuwt daar expliciet voor)
 * faalt hierdoor CI i.p.v. onopgemerkt door te glippen.
 */
@QuarkusTest
// Mock-profiel om dezelfde reden als de overige endpoint-tests: zonder profiel zou de
// (inactieve) Redis-client de boot laten falen nu de testsuite geen quarkus.redis.hosts
// meer zet (dat zou Dev Services voor de keten-E2E onderdrukken).
@TestProfile(MockSessiecacheProfile::class)
class BijlageContentTypeFilterQuarkusTest {

    @Test
    fun `onparsebaar MIME-type valt end-to-end terug op octet-stream + attachment`() {
        given()
            .queryParam("mime", "not-a-mime-type")
            .`when`()
            .get("/test-only/bijlage-mime")
            .then()
            .statusCode(200)
            .header("Content-Type", equalTo("application/octet-stream"))
            .header("Content-Disposition", equalTo("attachment"))
    }

    @Test
    fun `parsebaar MIME-type komt end-to-end 1-op-1 door met attachment`() {
        given()
            .queryParam("mime", "application/pdf")
            .`when`()
            .get("/test-only/bijlage-mime")
            .then()
            .statusCode(200)
            .header("Content-Type", equalTo("application/pdf"))
            .header("Content-Disposition", equalTo("attachment"))
    }
}
