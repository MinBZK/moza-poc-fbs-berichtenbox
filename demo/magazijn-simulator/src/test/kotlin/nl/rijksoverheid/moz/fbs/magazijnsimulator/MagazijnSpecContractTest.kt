package nl.rijksoverheid.moz.fbs.magazijnsimulator

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Toetst de antwoorden van de simulator rechtstreeks tegen `berichtenmagazijn-api.yaml` — dezelfde
 * spec waar het echte magazijn uit genereert.
 *
 * Dit is de kern van de belofte dat een gesimuleerd magazijn van buiten niet te onderscheiden is,
 * en die belofte hangt niet aan de generator: die bepaalt de vorm van de interface, niet die van
 * wat er daadwerkelijk over de lijn gaat. Alles wat in de resource wordt samengesteld — de
 * paginering-velden, de HAL-links, de foutbodies — passeert de generator ongezien.
 *
 * De spec staat op het test-classpath doordat de build hem uit de magazijn-module kopieert; een
 * tweede exemplaar in git zou precies de drift toelaten die deze module moet uitsluiten.
 */
@QuarkusTest
class MagazijnSpecContractTest : MagazijnTestBasis() {

    /**
     * `withBasePathOverride` vertelt de validator dat de operaties van de spec hier achter het
     * magazijn-prefix zitten. Zonder die regel zou hij de paden niet herkennen en niets valideren —
     * en dan meldt hij groen zonder iets gemeten te hebben.
     *
     * De HAL-`_links.*.href` zijn URI-references; networknt dwingt `format: uri` strikt als
     * absolute RFC 3986 URI af, dus die assertie op WARN — gelijk aan wat de contracttests van het
     * magazijn zelf doen, zodat beide kanten dezelfde lat hanteren.
     */
    private val specValidatie = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtenmagazijn-api.yaml")
            .withBasePathOverride(BASIS)
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.response.body.schema.format.uri", ValidationReport.Level.WARN)
                    .build(),
            )
            .build(),
    )

    @Test
    fun `de lege berichtenlijst voldoet aan de spec`() {
        given()
            .filter(specValidatie)
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
    }

    @Test
    fun `de lege berichtenlijst voldoet ook met paginering en afzenderfilter aan de spec`() {
        given()
            .filter(specValidatie)
            .header(ONTVANGER_HEADER, ONTVANGER)
            .queryParam("page", 0)
            .queryParam("pageSize", 5)
            .queryParam("afzender", MAGAZIJN)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
    }

    @Test
    fun `de 404 op een bericht voldoet aan het Problem-schema`() {
        given()
            .filter(specValidatie)
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten/$BERICHT_ID")
            .then()
            .statusCode(404)
    }

    @Test
    fun `de 404 op een bijlage voldoet aan het Problem-schema`() {
        given()
            .filter(specValidatie)
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten/$BERICHT_ID/bijlagen/$BIJLAGE_ID")
            .then()
            .statusCode(404)
    }

    @Test
    fun `de 404 op status bijwerken voldoet aan het Problem-schema`() {
        given()
            .filter(specValidatie)
            .header(ONTVANGER_HEADER, ONTVANGER)
            .contentType("application/merge-patch+json")
            .body("""{"gelezen": true}""")
            .`when`().patch("$BASIS/berichten/$BERICHT_ID")
            .then()
            .statusCode(404)
    }

    @Test
    fun `de 404 op verwijderen voldoet aan het Problem-schema`() {
        given()
            .filter(specValidatie)
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().delete("$BASIS/berichten/$BERICHT_ID")
            .then()
            .statusCode(404)
    }

    /**
     * Nul, één en twee bijlagen. Bij een leeg magazijn stelt de mapper nul samenvattingen samen en
     * ziet de validator dus geen enkel veld dat de resource zelf invult; pas een gevuld antwoord
     * legt [nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten.BerichtDtoMapper] langs het schema.
     * De cardinaliteiten staan er alle drie omdat de `bijlagen`-array bij nul een andere vorm heeft
     * dan bij meer, en het schema beide moet toestaan.
     */
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `een gevuld bericht voldoet aan de spec`(aantalBijlagen: Int) {
        val berichtId = leverAan(aantalBijlagen)

        given()
            .filter(specValidatie)
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(200)
            .body("bijlagen.size()", equalTo(aantalBijlagen))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `een gevulde berichtenlijst voldoet aan de spec`(aantalBijlagen: Int) {
        leverAan(aantalBijlagen)

        given()
            .filter(specValidatie)
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(1))
    }

    /**
     * De paginering-links komen alleen op een lijst met meer dan één pagina voor, en `next` en
     * `prev` staan er dan elk op een andere pagina. Beide kanten dus, anders blijft de helft van
     * `PaginationLinks` ongetoetst.
     */
    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `de paginering-links voldoen aan de spec`(pagina: Int) {
        repeat(3) { leverAan(aantalBijlagen = 0) }

        given()
            .filter(specValidatie)
            .header(ONTVANGER_HEADER, ONTVANGER)
            .queryParam("page", pagina)
            .queryParam("pageSize", 2)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
    }

    @Test
    fun `een bijlage-download voldoet aan de spec`() {
        val berichtId = leverAan(aantalBijlagen = 1)

        val bijlageId = given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten/$berichtId")
            .then()
            .extract().path<String>("bijlagen[0].bijlageId")

        given()
            .filter(specValidatie)
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(200)
    }

    @Test
    fun `een geslaagde statuswijziging voldoet aan de spec`() {
        val berichtId = leverAan(aantalBijlagen = 0)

        given()
            .filter(specValidatie)
            .header(ONTVANGER_HEADER, ONTVANGER)
            .contentType(MERGE_PATCH_JSON)
            .body("""{"gelezen": true, "map": "Belastingen"}""")
            .`when`().patch("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(200)
    }

    @Test
    fun `een geslaagde verwijdering voldoet aan de spec`() {
        val berichtId = leverAan(aantalBijlagen = 0)

        given()
            .filter(specValidatie)
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().delete("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(204)
    }

    @Test
    fun `een geslaagde aanlevering voldoet aan de spec, request en response`() {
        given()
            .filter(specValidatie)
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$MAGAZIJN",
                  "ontvanger": {"type": "KVK", "waarde": "90000001"},
                  "onderwerp": "Contracttest",
                  "inhoud": "Contracttest inhoud"
                }
                """.trimIndent(),
            )
            .`when`().post("$BASIS/aanleveringen")
            .then()
            .statusCode(201)
    }

    private fun leverAan(aantalBijlagen: Int): String {
        val bijlagenJson = if (aantalBijlagen == 0) {
            ""
        } else {
            (1..aantalBijlagen).joinToString(prefix = """, "bijlagen": [""", postfix = "]") {
                """{"naam": "bijlage-$it.pdf", "mimeType": "application/pdf", "inhoud": "cGRm"}"""
            }
        }

        return given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$MAGAZIJN",
                  "ontvanger": {"type": "KVK", "waarde": "90000001"},
                  "onderwerp": "Contracttest",
                  "inhoud": "Contracttest inhoud"$bijlagenJson
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
        const val MAGAZIJN = "00000009000000000001"
        const val BASIS = "/magazijn/$MAGAZIJN/api/v1"
        const val BERICHT_ID = "11111111-2222-3333-4444-555555555555"
        const val BIJLAGE_ID = "66666666-7777-8888-9999-000000000000"
        const val MERGE_PATCH_JSON = "application/merge-patch+json"
    }
}
