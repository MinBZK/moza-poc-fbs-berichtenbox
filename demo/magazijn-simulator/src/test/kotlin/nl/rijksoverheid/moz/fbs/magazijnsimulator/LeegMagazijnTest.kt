package nl.rijksoverheid.moz.fbs.magazijnsimulator

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test

/**
 * Wat elk van de zes operaties doet zolang de simulator nog niets opslaat. Dat is geen tijdelijke
 * uitzondering maar de toestand van een leeg magazijn — met één verschil: aanleveren kán niet, en
 * zegt dat ook, in plaats van een bevestiging te geven voor iets dat nergens terechtkomt.
 *
 * Elk antwoord noemt het magazijn waar het vandaan komt. Bij honderd magazijnen is "niet gevonden"
 * zonder die vermelding niet te onderscheiden van "op het verkeerde magazijn uitgekomen".
 */
@QuarkusTest
class LeegMagazijnTest {

    @Test
    fun `een bericht opvragen levert 404 met het magazijn erbij`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten/$BERICHT_ID")
            .then()
            .statusCode(404)
            .contentType(PROBLEM_JSON)
            .body("status", equalTo(404))
            .body("detail", containsString(MAGAZIJN))
    }

    @Test
    fun `een bijlage opvragen levert 404`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten/$BERICHT_ID/bijlagen/$BIJLAGE_ID")
            .then()
            .statusCode(404)
            .contentType(PROBLEM_JSON)
            .body("detail", containsString(MAGAZIJN))
    }

    @Test
    fun `status bijwerken levert 404`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .contentType("application/merge-patch+json")
            .body("""{"gelezen": true, "map": "Archief"}""")
            .`when`().patch("$BASIS/berichten/$BERICHT_ID")
            .then()
            .statusCode(404)
            .contentType(PROBLEM_JSON)
            .body("detail", containsString(MAGAZIJN))
    }

    @Test
    fun `verwijderen levert 404`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().delete("$BASIS/berichten/$BERICHT_ID")
            .then()
            .statusCode(404)
            .contentType(PROBLEM_JSON)
            .body("detail", containsString(MAGAZIJN))
    }

    /**
     * Bewust 503 en geen 201: een bevestigde aanlevering die daarna nergens te vinden is, valt pas
     * stroomafwaarts op — bij de uitvraag, of pas in een demo.
     */
    @Test
    fun `aanleveren zegt dat er nog niets opgeslagen kan worden`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$MAGAZIJN",
                  "ontvanger": {"type": "KVK", "waarde": "90000001"},
                  "onderwerp": "Aanlevering tijdens de fundament-stap",
                  "inhoud": "Deze aanlevering hoort geweigerd te worden."
                }
                """.trimIndent(),
            )
            .`when`().post("$BASIS/aanleveringen")
            .then()
            .statusCode(503)
            .contentType(PROBLEM_JSON)
            .body("status", equalTo(503))
            .body("detail", containsString(MAGAZIJN))
    }

    @Test
    fun `een ongeldige ontvanger-header levert 400 problem+json, niet het violation-rapport van Quarkus`() {
        given()
            .header(ONTVANGER_HEADER, "BSN:kaas")
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(400)
            .contentType(PROBLEM_JSON)
            .body("status", equalTo(400))
    }

    @Test
    fun `een ontbrekende ontvanger-header levert 400 problem+json`() {
        given()
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(400)
            .contentType(PROBLEM_JSON)
    }

    /**
     * De spec declareert `API-Version` op élke response, ook op de foutresponses. De waarde komt
     * uit `ApiInfo.SPEC_VERSION`, dat op build-time uit diezelfde spec wordt gegenereerd — precies
     * zoals het echte magazijn hem afleidt, zodat een client de twee niet uit elkaar houdt.
     */
    @Test
    fun `elke response draagt de API-versie uit de spec`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .header("API-Version", ApiInfo.SPEC_VERSION)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten/$BERICHT_ID")
            .then()
            .statusCode(404)
            .header("API-Version", ApiInfo.SPEC_VERSION)
    }

    /**
     * `instance` draagt het correlatie-id waarmee support een melding terugvindt. Het echte magazijn
     * zet hem op elk foutantwoord; ontbreekt hij hier, dan is de simulator juist op zijn foutpad te
     * herkennen.
     */
    @Test
    fun `elk foutantwoord draagt een correlatie-id`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten/$BERICHT_ID")
            .then()
            .statusCode(404)
            .body("instance", startsWith("urn:uuid:"))

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/00000009000000009999/api/v1/berichten")
            .then()
            .statusCode(404)
            .body("instance", startsWith("urn:uuid:"))
    }

    /**
     * Fouten die Quarkus zélf opwerpt — niet onze code — horen net zo goed als `problem+json` naar
     * buiten te komen. Zonder mapper levert dit een lege body of een HTML-pagina op, en dan wijkt
     * de simulator op zijn foutpad af van het echte magazijn.
     */
    @Test
    fun `een niet-ondersteund request-mediatype levert 415 problem+json`() {
        given()
            .contentType(ContentType.TEXT)
            .body("geen json")
            .`when`().post("$BASIS/aanleveringen")
            .then()
            .statusCode(415)
            .contentType(PROBLEM_JSON)
            .body("status", equalTo(415))
    }

    @Test
    fun `een onhaalbare Accept-header levert 406 problem+json`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .accept("application/xml")
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(406)
            .contentType(PROBLEM_JSON)
            .body("status", equalTo(406))
    }

    /**
     * Het pad-filter staat vóór het matchen en ziet daarmee elk JAX-RS-request. Dat het de
     * beheerpaden van Quarkus zelf níét afvangt, is geen aanname maar iets om vast te pinnen: een
     * gezondheidscontrole die stilzwijgend 404 gaat geven, haalt op ZAD de hele pod om.
     */
    @Test
    fun `de gezondheidscontrole blijft bereikbaar zonder magazijn-prefix`() {
        given()
            .`when`().get("/q/health/ready")
            .then()
            .statusCode(200)
    }

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000001"
        const val PROBLEM_JSON = "application/problem+json"
        const val MAGAZIJN = "00000009000000000001"
        const val BASIS = "/magazijn/$MAGAZIJN/api/v1"
        const val BERICHT_ID = "11111111-2222-3333-4444-555555555555"
        const val BIJLAGE_ID = "66666666-7777-8888-9999-000000000000"
    }
}
