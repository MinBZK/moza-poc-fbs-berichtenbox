package nl.rijksoverheid.moz.fbs.magazijnsimulator

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import nl.rijksoverheid.moz.fbs.kern.testhulp.Logvanger
import nl.rijksoverheid.moz.fbs.kern.testhulp.QUARKUS_REST_MET_INVOER
import nl.rijksoverheid.moz.fbs.kern.testhulp.herkenbareDelen
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Wie een ontvanger met een fout aanlevert of opvraagt, krijgt een 400 — en mag daarmee geen stuk
 * van het BSN in de applicatielog zetten, op geen enkel niveau. De melding van Jackson citeert de
 * invoer; de log noemt daarom alleen het id, het type, het veld en de positie.
 *
 * In de body valt elke waarde op een andere plek af: met een letter erin bij de spec-regex, te kort
 * bij de lengtecontrole, negen cijfers bij de elfproef. In `X-Ontvanger` vangt de spec-regex ook de
 * te korte waarde al af. Waar een domeininvariant de waarde afwijst, is er geen veld om te noemen;
 * die gevallen bewaken dat de log ook daar niets van de waarde overneemt.
 */
@QuarkusTest
class PersoonsgegevensUitDeLogTest {

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("aanleveringen")
    fun `een aanlevering met een foute ontvanger zet geen stuk ervan in de log`(
        geval: String,
        waarde: String,
        ontvanger: String,
        verwachtInLog: String?,
    ) {
        val (antwoord, log) = metLog {
            given()
                .contentType(ContentType.JSON)
                .body(
                    """
                    {
                      "afzender": "$MAGAZIJN",
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

        // Negen cijfers passen op de spec-regex en vallen pas bij de elfproef af.
        val verwacht = if (waarde.length == 9 && waarde.all(Char::isDigit)) null else "getBerichten.arg0 (Pattern)"

        assertAlleenIdEnVeld(antwoord, log, waarde, verwacht)
    }

    @Test
    fun `de categorieën van Quarkus REST die een onleesbare body loggen, staan vast op INFO`() {
        QUARKUS_REST_MET_INVOER.forEach { categorie ->
            val niveau = ConfigProvider.getConfig().getValue("quarkus.log.category.\"$categorie\".level", String::class.java)

            assertEquals("INFO", niveau, categorie)
        }
    }

    private fun metLog(verzoek: () -> ExtractableResponse<Response>): Pair<ExtractableResponse<Response>, String> =
        Logvanger().use { logvanger -> verzoek() to logvanger.tekst() }

    private fun assertAlleenIdEnVeld(
        antwoord: ExtractableResponse<Response>,
        log: String,
        waarde: String,
        verwachtInLog: String?,
    ) {
        assertEquals(400, antwoord.statusCode(), antwoord.asString())

        val foutId = antwoord.path<String>("instance").removePrefix("urn:uuid:")
        assertTrue(log.contains(foutId), "het id uit 'instance' hoort in de log: $log")

        verwachtInLog?.let { assertTrue(log.contains(it), "'$it' hoort in de log: $log") }

        herkenbareDelen(waarde).forEach { deel ->
            assertFalse(log.contains(deel), "'$deel' uit '$waarde' hoort niet in de log: $log")
        }
    }

    companion object {
        private const val MAGAZIJN = "00000009000000000001"
        private const val BASIS = "/magazijn/$MAGAZIJN/api/v1"

        @JvmStatic
        fun aanleveringen() = listOf("99999365", "99999365x", "999993654").flatMap { waarde ->
            val opRegex = if (waarde.all(Char::isDigit)) null else "leverBerichtAan.arg0.ontvanger.waarde (Pattern)"

            // "Als tekst" is de vorm uit de header in de body; Jackson citeert de tekst letterlijk.
            // "Onleesbaar" begint met een letter: alleen dan noemt Jackson het hele token.
            listOf(
                Arguments.of("als tekst", waarde, "\"BSN:$waarde\"", "veld=ontvanger,"),
                Arguments.of("als waarde", waarde, """{"type": "BSN", "waarde": "$waarde"}""", opRegex),
                Arguments.of("als type", waarde, """{"type": "$waarde", "waarde": "999993653"}""", "veld=ontvanger.type,"),
                Arguments.of("als lijst", waarde, """{"type": "BSN", "waarde": ["$waarde"]}""", "veld=ontvanger.waarde,"),
                Arguments.of("onleesbaar", waarde, """{"type": "BSN", "waarde": x$waarde}""", "positie=3:"),
            )
        }
    }
}
