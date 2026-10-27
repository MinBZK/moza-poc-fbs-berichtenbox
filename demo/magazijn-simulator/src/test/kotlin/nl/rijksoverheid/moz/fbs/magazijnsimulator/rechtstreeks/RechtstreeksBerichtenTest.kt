package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.App
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.COMPACT_JWE
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.MAGAZIJN_A
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.MAGAZIJN_B
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.basis
import nl.rijksoverheid.moz.fbs.rechtstreeks.TestApp
import org.hamcrest.Matchers.containsString
import org.jose4j.json.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock

/**
 * Berichten lezen via de tweede ingang: de ontvanger komt uit de magazijnsessie, het antwoord is
 * hetzelfde model als op de eerste ingang, versleuteld en met links naar de paden van deze ingang.
 */
@QuarkusTest
@TestProfile(RechtstreeksTest.Profiel::class)
class RechtstreeksBerichtenTest : MagazijnTestBasis() {

    @Inject
    lateinit var klok: Clock

    /** Een app met een sessie bij magazijn A, voor de ontvanger uit zijn credential. */
    private inner class Sessie {
        val app = App(klok.instant())
        val token = app.sessie(MAGAZIJN_A)

        fun lijst(query: String = ""): Map<String, Any> = lees(
            given().header("Authorization", token).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten$query")
                .then().statusCode(200).contentType("application/jose").extract().asString(),
        )

        fun lees(jwe: String): Map<String, Any> {
            assertTrue(COMPACT_JWE.matches(jwe))

            return JsonUtil.parseJson(TestApp.ontsleutelDir(jwe, app.aes))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3])
    fun `de lijst bevat precies de berichten van de ontvanger uit de sessie`(aantal: Int) {
        repeat(aantal) { leverAan(ONTVANGER, "Eigen bericht $it") }
        leverAan(ANDERE_ONTVANGER, "Bericht van een ander")

        val lijst = Sessie().lijst()
        val berichten = lijst["berichten"] as List<*>

        assertEquals(aantal, berichten.size)
        assertEquals(aantal.toLong(), lijst["totalElements"])
        assertTrue(berichten.none { (it as Map<*, *>)["onderwerp"] == "Bericht van een ander" })
    }

    @Test
    fun `de lijst is hetzelfde model als op de eerste ingang, met links naar deze ingang`() {
        repeat(3) { leverAan(ONTVANGER, "Bericht $it") }

        val versleuteld = Sessie().lijst("?page=1&pageSize=2")
        val eersteIngang = given().header("X-Ontvanger", ONTVANGER).get("${basis(MAGAZIJN_A)}/berichten?page=1&pageSize=2")
            .then().statusCode(200).extract().asString()
        val verwacht = JsonUtil.parseJson(eersteIngang.replace("/api/v1/berichten", "/api/v1/rechtstreeks/berichten"))

        assertEquals(verwacht, versleuteld)
        assertTrue(JsonUtil.toJson(versleuteld).contains("/magazijn/$MAGAZIJN_A/api/v1/rechtstreeks/berichten"))
    }

    @Test
    fun `een eigen bericht komt met inhoud, versleuteld, en met een self naar deze ingang`() {
        val berichtId = leverAan(ONTVANGER, "Met inhoud")
        val sessie = Sessie()

        val jwe = given().header("Authorization", sessie.token).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$berichtId")
            .then().statusCode(200).contentType("application/jose").header("Cache-Control", "no-store").extract().asString()
        val bericht = sessie.lees(jwe)

        assertEquals(berichtId, bericht["berichtId"])
        assertEquals("Inhoud van een demo-bericht.", bericht["inhoud"])
        assertEquals(
            "/magazijn/$MAGAZIJN_A/api/v1/rechtstreeks/berichten/$berichtId",
            java.net.URI.create(((bericht["_links"] as Map<*, *>)["self"] as Map<*, *>)["href"] as String).path,
        )
    }

    @Test
    fun `een bericht van een ander, een verwijderd of een onbekend bericht geeft 404`() {
        val vanEenAnder = leverAan(ANDERE_ONTVANGER, "Niet voor jou")
        val verwijderd = leverAan(ONTVANGER, "Weggegooid")

        given().header("X-Ontvanger", ONTVANGER).delete("${basis(MAGAZIJN_A)}/berichten/$verwijderd").then().statusCode(204)

        val sessie = Sessie()

        listOf(vanEenAnder, verwijderd, "11111111-2222-3333-4444-555555555555").forEach { id ->
            valideer().header("Authorization", sessie.token).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$id")
                .then().statusCode(404)
                .contentType("application/problem+json")
                .body("type", containsString("bericht-onbekend"))
                .header("Access-Control-Allow-Origin", "*")
        }
    }

    @Test
    fun `zonder geldige sessie, of met een sessie van een ander magazijn, geeft elk berichtpad 401`() {
        val berichtId = leverAan(ONTVANGER, "Afgeschermd")
        val token = Sessie().token

        listOf("berichten", "berichten/$berichtId").forEach { pad ->
            valideer().get("${basis(MAGAZIJN_A)}/rechtstreeks/$pad").then().statusCode(401)
            valideer().header("Authorization", "onbekend").get("${basis(MAGAZIJN_A)}/rechtstreeks/$pad").then().statusCode(401)
            given().header("Authorization", token).get("${basis(MAGAZIJN_B)}/rechtstreeks/$pad")
                .then().statusCode(401).header("WWW-Authenticate", "Magazijnsessie realm=\"$MAGAZIJN_B\"")
        }
    }

    @Test
    fun `een paginering buiten de grenzen van het contract geeft 400`() {
        val token = Sessie().token

        listOf("pageSize=0", "pageSize=101", "page=-1").forEach { query ->
            valideer().header("Authorization", token).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten?$query")
                .then().statusCode(400).contentType("application/problem+json")
        }
    }

    @Test
    fun `de antwoorden op de berichtpaden voldoen aan het contract`() {
        val berichtId = leverAan(ONTVANGER, "Contract")
        val token = Sessie().token

        valideer().header("Authorization", token).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten").then().statusCode(200)
        valideer().header("Authorization", token).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$berichtId").then().statusCode(200)
    }

    private fun leverAan(ontvanger: String, onderwerp: String): String {
        val (type, waarde) = ontvanger.split(':')

        return given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$MAGAZIJN_A",
                  "ontvanger": {"type": "$type", "waarde": "$waarde"},
                  "onderwerp": "$onderwerp",
                  "inhoud": "Inhoud van een demo-bericht."
                }
                """.trimIndent(),
            )
            .post("${basis(MAGAZIJN_A)}/aanleveringen")
            .then().statusCode(201)
            .extract().path("berichtId")
    }

    private fun valideer() = given().filter(
        OpenApiValidationFilter(
            OpenApiInteractionValidator.createForSpecificationUrl("openapi/rechtstreeks-ophalen-api.yaml")
                .withBasePathOverride(basis(MAGAZIJN_A))
                .withLevelResolver(
                    LevelResolver.create()
                        .withLevel("validation.request", ValidationReport.Level.IGNORE)
                        .build(),
                )
                .build(),
        ),
    )

    private companion object {
        const val ONTVANGER = "KVK:90000014"
        const val ANDERE_ONTVANGER = "KVK:90000001"
    }
}
