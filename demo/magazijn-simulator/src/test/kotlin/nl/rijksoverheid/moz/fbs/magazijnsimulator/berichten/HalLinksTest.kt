package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.matchesRegex
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.Base64

/**
 * De HAL-links zijn absolute-path references met het magazijn-prefix: geen schema en geen host uit
 * het verzoek. Achter de ingress van ZAD komt het verzoek als plain http binnen, en de `Host`-header
 * kiest de aanroeper; geen van beide mag in een link terechtkomen.
 */
@QuarkusTest
class HalLinksTest : MagazijnTestBasis() {

    @Test
    fun `lijst negeert een vervalste Host en X-Forwarded-Proto http`() {
        repeat(3) { leverAan() }

        vervalst()
            .`when`().get("$BASIS/berichten?page=0&pageSize=2&afzender=$MAGAZIJN")
            .then()
            .statusCode(200)
            .body("_links.self.href", equalTo(lijst(page = 0, pageSize = 2, afzender = MAGAZIJN)))
            .body("_links.first.href", equalTo(lijst(page = 0, pageSize = 2, afzender = MAGAZIJN)))
            .body("_links.last.href", equalTo(lijst(page = 1, pageSize = 2, afzender = MAGAZIJN)))
            .body("_links.next.href", equalTo(lijst(page = 1, pageSize = 2, afzender = MAGAZIJN)))
            .body("berichten", hasSize<Any>(2))
            .body("berichten._links.self.href", everyItem(matchesRegex(BERICHT_HREF)))
    }

    @Test
    fun `detail en bijlage negeren een vervalste Host en X-Forwarded-Proto http`() {
        val berichtId = leverAan(metBijlage = true)

        val detail = vervalst()
            .`when`().get("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertEquals("$BASIS/berichten/$berichtId", detail.getString("_links.self.href"))
        assertEquals(
            "$BASIS/berichten/$berichtId/bijlagen/${detail.getString("bijlagen[0].bijlageId")}",
            detail.getString("bijlagen[0]._links.self.href"),
        )
    }

    @Test
    fun `aanleveren negeert een vervalste Host en X-Forwarded-Proto http`() {
        val antwoord = vervalst()
            .contentType(ContentType.JSON)
            .body(aanlevering(metBijlage = false))
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

    private fun leverAan(metBijlage: Boolean = false): String = given()
        .contentType(ContentType.JSON)
        .body(aanlevering(metBijlage))
        .`when`().post("$BASIS/aanleveringen")
        .then()
        .statusCode(201)
        .extract().path("berichtId")

    private fun aanlevering(metBijlage: Boolean): String {
        val bijlagen = if (metBijlage) {
            val inhoud = Base64.getEncoder().encodeToString("%PDF-1.4".toByteArray())

            """, "bijlagen": [{"naam": "test.pdf", "mimeType": "application/pdf", "inhoud": "$inhoud"}]"""
        } else {
            ""
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
        const val BERICHT_HREF = "^/magazijn/$MAGAZIJN/api/v1/berichten/[0-9a-f-]{36}$"
    }
}
