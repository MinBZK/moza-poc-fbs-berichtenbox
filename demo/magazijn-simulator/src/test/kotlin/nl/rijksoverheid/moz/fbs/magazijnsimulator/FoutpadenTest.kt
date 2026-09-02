package nl.rijksoverheid.moz.fbs.magazijnsimulator

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * De randen van het foutpad: wat er gebeurt als een bericht niet bestaat, als de invoer niet klopt,
 * en of alles wat naar buiten komt de vorm heeft die de spec voorschrijft.
 *
 * Elk niet-gevonden-antwoord noemt het magazijn waar het vandaan komt. Bij honderd magazijnen is
 * "niet gevonden" zonder die vermelding niet te onderscheiden van "op het verkeerde magazijn
 * uitgekomen".
 */
@QuarkusTest
class FoutpadenTest {

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
     * Domein-invarianten die de spec niet afdwingt maar het echte magazijn wél: hier de elfproef op
     * een BSN. Een simulator die dit accepteert, laat een aanlevering slagen die in werkelijkheid
     * met 400 wordt geweigerd.
     */
    @Test
    fun `een ontvanger die de elfproef niet doorstaat levert 400 problem+json`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$MAGAZIJN",
                  "ontvanger": {"type": "BSN", "waarde": "123456789"},
                  "onderwerp": "Ongeldige ontvanger",
                  "inhoud": "Deze aanlevering hoort geweigerd te worden."
                }
                """.trimIndent(),
            )
            .`when`().post("$BASIS/aanleveringen")
            .then()
            .statusCode(400)
            .contentType(PROBLEM_JSON)
            .body("status", equalTo(400))
    }

    @Test
    fun `afzender en ontvanger mogen niet hetzelfde nummer zijn`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$MAGAZIJN",
                  "ontvanger": {"type": "OIN", "waarde": "$MAGAZIJN"},
                  "onderwerp": "Aan zichzelf",
                  "inhoud": "Deze aanlevering hoort geweigerd te worden."
                }
                """.trimIndent(),
            )
            .`when`().post("$BASIS/aanleveringen")
            .then()
            .statusCode(400)
            .contentType(PROBLEM_JSON)
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

    /**
     * Kapotte JSON hoort net als bij het echte magazijn een `problem+json`-400 op te leveren, met
     * een melding die niets van Jackson doorgeeft: die noemt veldnamen, klassenamen en soms een stuk
     * van de aangeboden waarde, en dat laatste kan een BSN zijn.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"afzender":""",
            "dit is helemaal geen json",
            "[]",
            """{"afzender": 42}""",
        ],
    )
    fun `onleesbare of niet-passende JSON levert 400 problem+json zonder Jackson-details`(body: String) {
        val antwoord = given()
            .contentType(ContentType.JSON)
            .body(body)
            .`when`().post("$BASIS/aanleveringen")
            .then()
            .statusCode(400)
            .contentType(PROBLEM_JSON)
            .extract().asString()

        listOf("com.fasterxml", "nl.rijksoverheid", ".kt:", "at ").forEach { spoor ->
            assertFalse(antwoord.contains(spoor), "de foutbody hoort geen '$spoor' te bevatten")
        }
    }

    /**
     * Een MIME-type dat geen mediatype ís, hoort al bij het aanleveren te stranden. Zonder die
     * controle slaagt de aanlevering met 201 en is de bijlage daarna permanent onophaalbaar: er
     * valt geen `Content-Type` van te maken, dus elke download geeft 500.
     */
    @Test
    fun `een bijlage met een onbruikbaar MIME-type wordt bij het aanleveren geweigerd`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$MAGAZIJN",
                  "ontvanger": {"type": "KVK", "waarde": "90000001"},
                  "onderwerp": "Met een kapotte bijlage",
                  "inhoud": "Inhoud",
                  "bijlagen": [{"naam": "raar.bin", "mimeType": "kaas", "inhoud": "cGRm"}]
                }
                """.trimIndent(),
            )
            .`when`().post("$BASIS/aanleveringen")
            .then()
            .statusCode(400)
            .contentType(PROBLEM_JSON)
            .body("detail", containsString("type/subtype"))
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
