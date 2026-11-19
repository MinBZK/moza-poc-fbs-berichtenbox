package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Wie een ontvanger met een fout aanlevert of opvraagt, krijgt een 400 — en mag daarmee geen stuk
 * van het BSN in de applicatielog zetten, op geen enkel niveau. Een melding van Jackson of Bean
 * Validation citeert de invoer; de log noemt daarom alleen het id, het type en het veld.
 *
 * Elke waarde valt op een andere plek af: met een letter erin bij de spec-regex, te kort bij de
 * lengtecontrole, negen cijfers bij de elfproef.
 */
@QuarkusTest
class PersoonsgegevensUitDeLogTest {

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("aanleveringen")
    fun `een aanlevering met een foute ontvanger zet geen stuk ervan in de log`(
        geval: String,
        waarde: String,
        ontvanger: String,
        verwachtInLog: String,
    ) {
        val (antwoord, log) = metLog {
            given()
                .contentType(ContentType.JSON)
                .body(
                    """
                    {
                      "afzender": "$AFZENDER",
                      "ontvanger": $ontvanger,
                      "onderwerp": "Onderwerp",
                      "inhoud": "Inhoud"
                    }
                    """.trimIndent(),
                )
                .`when`().post("$BASIS/aanleveringen")
                .then()
                .extract()
        }

        assertAlleenIdEnVeld(antwoord, log, waarde, verwachtInLog)
    }

    @ParameterizedTest
    @ValueSource(strings = ["99999365", "99999365x", "999993654"])
    fun `een opvraging met een foute X-Ontvanger zet geen stuk ervan in de log`(waarde: String) {
        val (antwoord, log) = metLog {
            given()
                .header("X-Ontvanger", "BSN:$waarde")
                .`when`().get("$BASIS/berichten")
                .then()
                .extract()
        }

        assertAlleenIdEnVeld(antwoord, log, waarde, verwachtInLog = "")
    }

    private fun metLog(verzoek: () -> ExtractableResponse<Response>): Pair<ExtractableResponse<Response>, String> =
        Logvanger().use { logvanger -> verzoek() to logvanger.tekst() }

    private fun assertAlleenIdEnVeld(
        antwoord: ExtractableResponse<Response>,
        log: String,
        waarde: String,
        verwachtInLog: String,
    ) {
        assertEquals(400, antwoord.statusCode(), antwoord.asString())

        val foutId = antwoord.path<String>("instance").removePrefix("urn:uuid:")
        assertTrue(log.contains(foutId), "het id uit 'instance' hoort in de log: $log")
        assertTrue(log.contains(verwachtInLog), "'$verwachtInLog' hoort in de log: $log")

        herkenbareDelen(waarde).forEach { deel ->
            assertFalse(log.contains(deel), "'$deel' uit '$waarde' hoort niet in de log: $log")
        }
    }

    companion object {
        private const val AFZENDER = "00000001003214345000"
        private const val BASIS = "/api/v1"

        @JvmStatic
        fun aanleveringen() = listOf("99999365", "99999365x", "999993654").flatMap { waarde ->
            // De vorm uit de header in de body: Jackson citeert de tekst letterlijk in zijn melding.
            listOf(
                Arguments.of("als tekst", waarde, "\"BSN:$waarde\"", "veld=ontvanger,"),
                Arguments.of("als waarde", waarde, """{"type": "BSN", "waarde": "$waarde"}""", ""),
                Arguments.of("als type", waarde, """{"type": "$waarde", "waarde": "999993653"}""", ""),
                Arguments.of("als lijst", waarde, """{"type": "BSN", "waarde": ["$waarde"]}""", "veld=ontvanger.waarde,"),
                Arguments.of("onleesbaar", waarde, """{"type": "BSN", "waarde": ${waarde}x}""", ""),
            )
        }
    }
}
