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
    fun `onparsebaar MIME-type valt end-to-end terug op octet-stream + download`() {
        given()
            .queryParam("mime", "not-a-mime-type")
            .`when`()
            .get("/api/v1/test-only/bijlage-mime")
            .then()
            .statusCode(200)
            .header("Content-Type", equalTo("application/octet-stream"))
            .header("Content-Disposition", equalTo("attachment"))
    }

    @Test
    fun `parsebaar MIME-type komt end-to-end 1-op-1 door`() {
        given()
            .queryParam("mime", "application/pdf")
            .`when`()
            .get("/api/v1/test-only/bijlage-mime")
            .then()
            .statusCode(200)
            .header("Content-Type", equalTo("application/pdf"))
            .header("Content-Disposition", equalTo("inline"))
    }

    @Test
    fun `een veilig te tonen type mag end-to-end inline, met bestandsnaam`() {
        given()
            .queryParam("mime", "application/pdf")
            .queryParam("naam", "aanslag 2026.pdf")
            .`when`()
            .get("/api/v1/test-only/bijlage-mime")
            .then()
            .statusCode(200)
            .header("Content-Disposition", equalTo("inline; filename=\"aanslag_2026.pdf\"; filename*=UTF-8''aanslag%202026.pdf"))
    }

    @Test
    fun `een in de browser uitvoerbaar type blijft end-to-end een download`() {
        given()
            .queryParam("mime", "text/html")
            .queryParam("naam", "kwaad.html")
            .`when`()
            .get("/api/v1/test-only/bijlage-mime")
            .then()
            .statusCode(200)
            .header("Content-Type", equalTo("text/html"))
            .header("Content-Disposition", equalTo("attachment; filename=\"kwaad.html\"; filename*=UTF-8''kwaad.html"))
    }

    @Test
    fun `een naam met bijzondere tekens komt end-to-end heel door`() {
        given()
            .queryParam("mime", "application/pdf")
            .queryParam("naam", "Λογαριασμός\"; drop.pdf")
            .`when`()
            .get("/api/v1/test-only/bijlage-mime")
            .then()
            .statusCode(200)
            .header(
                "Content-Disposition",
                equalTo(
                    "inline; filename=\"______________drop.pdf\"; " +
                        "filename*=UTF-8''%CE%9B%CE%BF%CE%B3%CE%B1%CF%81%CE%B9%CE%B1%CF%83%CE%BC%CF%8C%CF%82%22%3B%20drop.pdf",
                ),
            )
    }
}
