package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.emptyIterable
import org.hamcrest.Matchers.endsWith
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import org.junit.jupiter.params.provider.CsvSource

/**
 * Twee dingen die nergens in de spec staan maar wel het waarneembare gedrag van het echte magazijn
 * zijn, en die een tweede implementatie dus alleen goed krijgt door ze op te schrijven.
 *
 * **De volgorde van 403 en 404.** Andermans bericht levert 403 op, óók als het al verwijderd is:
 * uit een verschil tussen 403 en 404 zou anders af te leiden zijn welke bericht-id's bestaan. Je
 * eigen verwijderde bericht levert wél 404 op, want dat bestaat voor jou niet meer.
 *
 * **De paginering.** `self`, `first` en `last` staan er altijd, `prev` en `next` alleen als die
 * pagina bestaat, en een pagina voorbij het einde is een lege lijst met 200 — geen 404.
 */
@QuarkusTest
class AutorisatieEnPagineringTest : MagazijnTestBasis() {

    @Test
    fun `andermans bericht opvragen levert 403 en geen inhoud`() {
        val berichtId = leverAan(ontvangerWaarde = "90000001")

        given()
            .header(ONTVANGER_HEADER, "KVK:90000002")
            .`when`().get("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(403)
            .contentType(PROBLEM_JSON)
    }

    /**
     * Hetzelfde nummer, ander type. Zonder het type mee te wegen zou `RSIN:999993653` toegang geven
     * tot een bericht voor `BSN:999993653`.
     */
    @Test
    fun `hetzelfde nummer met een ander type geeft geen toegang`() {
        val berichtId = leverAan(ontvangerType = "BSN", ontvangerWaarde = "999993653")

        given()
            .header(ONTVANGER_HEADER, "RSIN:999993653")
            .`when`().get("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(403)
    }

    @Test
    fun `andermans verwijderde bericht blijft 403 en wordt geen 404`() {
        val berichtId = leverAan(ontvangerWaarde = "90000001")

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().delete("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(204)

        given()
            .header(ONTVANGER_HEADER, "KVK:90000002")
            .`when`().delete("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(403)

        given()
            .header(ONTVANGER_HEADER, "KVK:90000002")
            .contentType(MERGE_PATCH_JSON)
            .body("""{"gelezen": true}""")
            .`when`().patch("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(403)
    }

    @Test
    fun `je eigen verwijderde bericht bijwerken levert 404`() {
        val berichtId = leverAan()

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().delete("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(204)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .contentType(MERGE_PATCH_JSON)
            .body("""{"gelezen": true}""")
            .`when`().patch("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(404)
    }

    @Test
    fun `een bijlage-id onder een ander bericht levert 404, geen bytes`() {
        val eerste = leverAan(bijlageNaam = "eerste.pdf")
        val tweede = leverAan(bijlageNaam = "tweede.pdf")

        val bijlageVanTweede = given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten/$tweede")
            .then()
            .extract().path<String>("bijlagen[0].bijlageId")

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten/$eerste/bijlagen/$bijlageVanTweede")
            .then()
            .statusCode(404)
    }

    @Test
    fun `de nieuwste berichten staan bovenaan`() {
        leverAan(onderwerp = "Eerst")
        leverAan(onderwerp = "Daarna")
        leverAan(onderwerp = "Laatst")

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("berichten.onderwerp", contains("Laatst", "Daarna", "Eerst"))
    }

    /**
     * Leeg, één en meerdere naast elkaar: bij precies één bericht ziet een paginering die altijd
     * alles teruggeeft er hetzelfde uit als een die echt pagineert.
     */
    @ParameterizedTest
    @CsvSource("0,1,0", "1,1,1", "5,2,3", "6,2,3")
    fun `de paginering telt en verdeelt zoals de spec belooft`(
        aantal: Int,
        pageSize: Int,
        verwachtePaginas: Int,
    ) {
        repeat(aantal) { leverAan(onderwerp = "Bericht $it") }

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .queryParam("pageSize", pageSize)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(aantal))
            .body("totalPages", equalTo(verwachtePaginas))
            .body("berichten.size()", equalTo(minOf(aantal, pageSize)))
    }

    @Test
    fun `op de eerste van meerdere paginas staat next maar geen prev`() {
        repeat(3) { leverAan() }

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .queryParam("pageSize", 2)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("_links.prev", nullValue())
            .body("_links.next.href", endsWith("page=1&pageSize=2"))
            .body("_links.last.href", endsWith("page=1&pageSize=2"))
    }

    @Test
    fun `op de laatste pagina staat prev maar geen next`() {
        repeat(3) { leverAan() }

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .queryParam("page", 1)
            .queryParam("pageSize", 2)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("_links.prev.href", endsWith("page=0&pageSize=2"))
            .body("_links.next", nullValue())
    }

    @Test
    fun `een pagina voorbij het einde is leeg en geen fout`() {
        leverAan()

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .queryParam("page", 9)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("berichten", emptyIterable<Any>())
            .body("totalElements", equalTo(1))
    }

    @Test
    fun `het afzenderfilter houdt alleen berichten van die afzender over`() {
        leverAan(afzender = MAGAZIJN)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .queryParam("afzender", ANDERE_AFZENDER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(0))
            .body("_links.self.href", endsWith("afzender=$ANDERE_AFZENDER"))
    }

    private fun leverAan(
        onderwerp: String = "Demo-bericht",
        afzender: String = MAGAZIJN,
        ontvangerType: String = "KVK",
        ontvangerWaarde: String = "90000001",
        bijlageNaam: String? = null,
    ): String {
        val bijlagenJson = bijlageNaam?.let {
            """, "bijlagen": [{"naam": "$it", "mimeType": "application/pdf", "inhoud": "cGRm"}]"""
        }.orEmpty()

        return given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$afzender",
                  "ontvanger": {"type": "$ontvangerType", "waarde": "$ontvangerWaarde"},
                  "onderwerp": "$onderwerp",
                  "inhoud": "Inhoud van een demo-bericht."$bijlagenJson
                }
                """.trimIndent(),
            )
            .`when`().post("$BASIS/aanleveringen")
            .then()
            .statusCode(201)
            .extract().path("berichtId")
    }

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000001"
        const val PROBLEM_JSON = "application/problem+json"
        const val MERGE_PATCH_JSON = "application/merge-patch+json"
        const val MAGAZIJN = "00000009000000000001"
        const val ANDERE_AFZENDER = "00000009000000000002"
        const val BASIS = "/magazijn/$MAGAZIJN/api/v1"
    }
}
