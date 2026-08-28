package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.emptyIterable
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Pint het gedrag van de pad-routering vast: welk magazijn er wordt gekozen, wat er gebeurt als er
 * geen te kiezen valt, en — het scherpste punt — dat het magazijn-prefix in de HAL-links terugkomt.
 *
 * Die laatste assertie is de reden dat deze test bestaat. De resources bouwen hun links uit
 * `UriInfo.baseUriBuilder`, en of de herschreven `baseUri` daar doorkomt is een eigenschap van de
 * JAX-RS-implementatie, niet van onze code. Breekt dat bij een Quarkus-upgrade, dan wijzen de links
 * van honderd magazijnen stilzwijgend naar het verkeerde adres; hier valt het om.
 */
@QuarkusTest
class MagazijnPadFilterTest {

    @Test
    fun `berichtenlijst van een bekend magazijn is leeg en spec-vormig`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/$MAGAZIJN_EEN/api/v1/berichten")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .body("berichten", emptyIterable<Any>())
            .body("page", equalTo(0))
            .body("pageSize", equalTo(20))
            .body("totalElements", equalTo(0))
            .body("totalPages", equalTo(0))
    }

    @Test
    fun `de HAL-links dragen de OIN van het aangeroepen magazijn`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/$MAGAZIJN_EEN/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("_links.self.href", containsString("/magazijn/$MAGAZIJN_EEN/api/v1/berichten"))
            .body("_links.first.href", containsString("/magazijn/$MAGAZIJN_EEN/api/v1/berichten"))
            .body("_links.last.href", containsString("/magazijn/$MAGAZIJN_EEN/api/v1/berichten"))
    }

    /**
     * Twee magazijnen naast elkaar, niet één: met één ingeschreven magazijn zou een filter dat
     * altijd het eerste kiest er net zo goed uitzien als een filter dat op de OIN discrimineert.
     */
    @Test
    fun `elk magazijn krijgt zijn eigen links, niet die van zijn buurman`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/$MAGAZIJN_TWEE/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("_links.self.href", containsString("/magazijn/$MAGAZIJN_TWEE/api/v1/berichten"))
            .body("_links.self.href", not(containsString(MAGAZIJN_EEN)))
    }

    @Test
    fun `query-parameters komen terug in de links`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .queryParam("page", 0)
            .queryParam("pageSize", 5)
            .queryParam("afzender", MAGAZIJN_TWEE)
            .`when`().get("/magazijn/$MAGAZIJN_EEN/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("pageSize", equalTo(5))
            .body("_links.self.href", containsString("pageSize=5"))
            .body("_links.self.href", containsString("afzender=$MAGAZIJN_TWEE"))
    }

    @Test
    fun `een onbekende OIN levert 404 met de OIN in het antwoord, geen ander magazijn`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/$ONBEKEND_MAGAZIJN/api/v1/berichten")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404))
            .body("detail", containsString(ONBEKEND_MAGAZIJN))
    }

    /**
     * Alles wat niet de vorm `/magazijn/<OIN>/api/v1/…` heeft, hoort dezelfde nette 404 te geven —
     * ook een OIN-achtige waarde die het niet is. Zonder de magazijn-root zou het filter moeten
     * gokken wat een eerste padsegment betekent.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "/api/v1/berichten",
            "/berichten",
            "/magazijn",
            "/magazijn/$MAGAZIJN_EEN",
            "/magazijn/$MAGAZIJN_EEN/berichten",
            "/magazijn/$MAGAZIJN_EEN/api/berichten",
            "/magazijn/$MAGAZIJN_EEN/api/v2/berichten",
            "/magazijn/12345/api/v1/berichten",
            "/magazijn/000000090000000000011/api/v1/berichten",
        ],
    )
    fun `een pad zonder bruikbaar magazijn-prefix levert 404 problem+json`(pad: String) {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get(pad)
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404))
    }

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000001"
        const val MAGAZIJN_EEN = "00000009000000000001"
        const val MAGAZIJN_TWEE = "00000009000000000002"
        const val ONBEKEND_MAGAZIJN = "00000009000000009999"
    }
}
