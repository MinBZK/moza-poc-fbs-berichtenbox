package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import io.restassured.specification.RequestSpecification
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.Base64

/**
 * De HAL-links zijn absolute-path references met het magazijn-prefix: geen schema en geen host uit
 * het verzoek. Achter de ingress van ZAD komt het verzoek als plain http binnen, en buiten ZAD
 * (compose, lokaal, achter een andere proxy) kiest de aanroeper de `Host`-header; geen van beide mag
 * in een link terechtkomen.
 */
@QuarkusTest
class HalLinksTest : MagazijnTestBasis() {

    @Test
    fun `lijst negeert een vervalste Host en X-Forwarded-Proto http`() {
        repeat(3) { leverAan() }

        val lijst = vervalst()
            .`when`().get("$BASIS/berichten?page=0&pageSize=2&afzender=$MAGAZIJN")
            .then()
            .statusCode(200)
            .body("_links.self.href", equalTo(lijst(page = 0, pageSize = 2, afzender = MAGAZIJN)))
            .body("_links.first.href", equalTo(lijst(page = 0, pageSize = 2, afzender = MAGAZIJN)))
            .body("_links.last.href", equalTo(lijst(page = 1, pageSize = 2, afzender = MAGAZIJN)))
            .body("_links.next.href", equalTo(lijst(page = 1, pageSize = 2, afzender = MAGAZIJN)))
            .body("berichten", hasSize<Any>(2))
            .extract().jsonPath()

        // Per bericht exact: een link die bij elk item naar hetzelfde bericht wijst, past ook op een
        // patroon.
        repeat(2) { i ->
            assertEquals(
                "$BASIS/berichten/${lijst.getString("berichten[$i].berichtId")}",
                lijst.getString("berichten[$i]._links.self.href"),
            )
        }
    }

    @Test
    fun `detail en bijlagen negeren een vervalste Host en X-Forwarded-Proto http`() {
        val berichtId = leverAan(aantalBijlagen = 2)

        val detail = vervalst()
            .`when`().get("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertEquals("$BASIS/berichten/$berichtId", detail.getString("_links.self.href"))
        assertBijlageLinks(berichtId, detail)
    }

    @Test
    fun `statuswijziging negeert een vervalste Host en X-Forwarded-Proto http`() {
        val berichtId = leverAan(aantalBijlagen = 2)

        val gewijzigd = vervalst()
            .contentType("application/merge-patch+json")
            .body("""{"gelezen": true}""")
            .`when`().patch("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertEquals("$BASIS/berichten/$berichtId", gewijzigd.getString("_links.self.href"))
        assertBijlageLinks(berichtId, gewijzigd)
    }

    @Test
    fun `aanleveren negeert een vervalste Host en X-Forwarded-Proto http`() {
        val antwoord = vervalst()
            .contentType(ContentType.JSON)
            .body(aanlevering(aantalBijlagen = 0))
            .`when`().post("$BASIS/aanleveringen")
            .then()
            .statusCode(201)
            .extract().jsonPath()

        assertEquals("$BASIS/berichten/${antwoord.getString("berichtId")}", antwoord.getString("_links.self.href"))
    }

    /**
     * Nul, één en meerdere pagina's; `prev` en `next` staan er alleen als die pagina bestaat, en
     * `last` wijst bij een lege lijst naar pagina 0.
     */
    @ParameterizedTest(name = "{0} berichten, pagina {1} van grootte 2")
    @CsvSource(
        "0, 0, 0, , ",
        "2, 0, 0, , ",
        "5, 0, 2, , 1",
        "5, 1, 2, 0, 2",
        "5, 2, 2, 1, ",
    )
    fun `paginering geeft prev en next alleen als die pagina bestaat`(
        aantal: Int,
        page: Int,
        laatste: Int,
        vorige: Int?,
        volgende: Int?,
    ) {
        repeat(aantal) { leverAan() }

        val antwoord = vervalst()
            .`when`().get("$BASIS/berichten?page=$page&pageSize=2")
            .then()
            .statusCode(200)
            .body("_links.self.href", equalTo(lijst(page = page, pageSize = 2)))
            .body("_links.first.href", equalTo(lijst(page = 0, pageSize = 2)))
            .body("_links.last.href", equalTo(lijst(page = laatste, pageSize = 2)))

        if (vorige == null) {
            antwoord.body("_links", not(hasKey("prev")))
        } else {
            antwoord.body("_links.prev.href", equalTo(lijst(page = vorige, pageSize = 2)))
        }

        if (volgende == null) {
            antwoord.body("_links", not(hasKey("next")))
        } else {
            antwoord.body("_links.next.href", equalTo(lijst(page = volgende, pageSize = 2)))
        }
    }

    private fun vervalst(): RequestSpecification = given()
        .header("X-Ontvanger", ONTVANGER)
        .header("Host", VREEMDE_HOST)
        .header("X-Forwarded-Proto", "http")
        .header("X-Forwarded-Host", VREEMDE_HOST)
        .header("Forwarded", "proto=http;host=$VREEMDE_HOST")

    private fun lijst(page: Int, pageSize: Int, afzender: String? = null): String =
        "$BASIS/berichten?page=$page&pageSize=$pageSize" + afzender?.let { "&afzender=$it" }.orEmpty()

    /** Elke bijlage-link exact, per bijlage-id; precies twee, zodat "steeds dezelfde" opvalt. */
    private fun assertBijlageLinks(berichtId: String, antwoord: JsonPath) {
        val aantal = antwoord.getList<Any>("bijlagen").size

        assertEquals(2, aantal)

        repeat(aantal) { i ->
            assertEquals(
                "$BASIS/berichten/$berichtId/bijlagen/${antwoord.getString("bijlagen[$i].bijlageId")}",
                antwoord.getString("bijlagen[$i]._links.self.href"),
            )
        }
    }

    private fun leverAan(aantalBijlagen: Int = 0): String = given()
        .contentType(ContentType.JSON)
        .body(aanlevering(aantalBijlagen))
        .`when`().post("$BASIS/aanleveringen")
        .then()
        .statusCode(201)
        .extract().path("berichtId")

    private fun aanlevering(aantalBijlagen: Int): String {
        val inhoud = Base64.getEncoder().encodeToString("%PDF-1.4".toByteArray())

        val bijlagen = if (aantalBijlagen == 0) {
            ""
        } else {
            val lijst = (1..aantalBijlagen).joinToString(", ") { n ->
                """{"naam": "bijlage-$n.pdf", "mimeType": "application/pdf", "inhoud": "$inhoud"}"""
            }

            """, "bijlagen": [$lijst]"""
        }

        return """
            {
              "afzender": "$MAGAZIJN",
              "ontvanger": {"type": "KVK", "waarde": "90000001"},
              "onderwerp": "Demo-bericht",
              "inhoud": "Inhoud van een demo-bericht."$bijlagen
            }
        """.trimIndent()
    }

    private companion object {
        const val ONTVANGER = "KVK:90000001"
        const val MAGAZIJN = "00000009000000000001"
        const val BASIS = "/magazijn/$MAGAZIJN/api/v1"
        const val VREEMDE_HOST = "evil.example"
    }
}
