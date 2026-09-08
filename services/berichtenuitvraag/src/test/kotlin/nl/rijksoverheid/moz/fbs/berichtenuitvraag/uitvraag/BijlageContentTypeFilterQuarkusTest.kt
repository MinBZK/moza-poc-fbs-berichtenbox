package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * End-to-end dekking voor [BijlageContentTypeFilter]. Drijft de filter via
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

    // Het scenario waar de statusgrens voor bestaat, langs het échte pad: de resource zet
    // de property en gooit daarna, zoals het fail-closed logboek in productie doet. De
    // pure tests fabriceren een status en zouden ook slagen als het filter helemaal niet
    // op een gemapte foutresponse zou draaien — deze test toetst dát het dat doet.
    @ParameterizedTest
    @ValueSource(ints = [403, 500, 503])
    fun `een fout na het zetten van de property komt als problem-json aan, niet als bijlage`(status: Int) {
        given()
            .queryParam("mime", "application/pdf")
            .queryParam("naam", "aanslag 2026.pdf")
            .queryParam("faalNa", status)
            .`when`()
            .get("/api/v1/test-only/bijlage-mime")
            .then()
            .statusCode(status)
            .contentType("application/problem+json")
            .header("Content-Disposition", nullValue())
            // En dus ook niet de versoepelde frame-headers: die hangen aan de dispositie,
            // die er op een foutresponse niet meer op staat.
            .header("X-Frame-Options", equalTo("DENY"))
    }

    // De versmalling hangt aan de dispositie en niet aan het pad: precies de bijlagen die
    // een browser mag tónen, mag een berichtenbox ook ínsluiten. Eén bron voor beide.
    //
    // Alle drie de inline-veilige typen, want `img-src` en `object-src` zijn twee
    // verschillende renderpaden; met alleen een PDF blijft de helft ongetest.
    @ParameterizedTest
    @ValueSource(strings = ["application/pdf", "image/png", "image/jpeg"])
    fun `een inline-bijlage mag door de eigen berichtenbox ingesloten worden`(mime: String) {
        given()
            .queryParam("mime", mime)
            .queryParam("naam", "aanslag.pdf")
            .`when`()
            .get("/api/v1/test-only/bijlage-mime")
            .then()
            .statusCode(200)
            .header("X-Frame-Options", equalTo("SAMEORIGIN"))
            .header(
                "Content-Security-Policy",
                equalTo(
                    "default-src 'none'; img-src 'self'; object-src 'self'; base-uri 'none'; " +
                        "form-action 'none'; frame-ancestors 'self'",
                ),
            )
            // Het hele veiligheidsargument onder `inline` leunt hierop: zonder nosniff mag
            // een browser er alsnog HTML in zien. De versmalling raakt twee headers, en
            // deze assertie legt vast dat het bij die twee blijft.
            .header("X-Content-Type-Options", equalTo("nosniff"))
            .header("Referrer-Policy", equalTo("no-referrer"))
            .header("Cache-Control", equalTo("no-store"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["text/html", "image/svg+xml", "application/octet-stream"])
    fun `een bijlage die een download blijft, valt ook niet in te sluiten`(mime: String) {
        given()
            .queryParam("mime", mime)
            .queryParam("naam", "kwaad.html")
            .`when`()
            .get("/api/v1/test-only/bijlage-mime")
            .then()
            .statusCode(200)
            .header("X-Frame-Options", equalTo("DENY"))
            .header(
                "Content-Security-Policy",
                equalTo("default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"),
            )
            .header("X-Content-Type-Options", equalTo("nosniff"))
    }

    // De versmalling mag niet uitlekken naar een gewoon endpoint op hetzelfde pad-prefix.
    // De statuscode staat er expliciet bij: zonder dat zou deze test ook slagen wanneer het
    // endpoint iets heel anders gaat doen, en test hij niet meer wat hij bedoelt.
    @Test
    fun `een endpoint zonder bijlage-dispositie houdt DENY`() {
        given()
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .header("X-Frame-Options", equalTo("DENY"))
    }
}
