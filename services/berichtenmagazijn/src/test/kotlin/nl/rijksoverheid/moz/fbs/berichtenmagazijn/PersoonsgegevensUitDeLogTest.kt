package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import nl.rijksoverheid.moz.fbs.kern.testhulp.FRAMEWORK_WORTELS
import nl.rijksoverheid.moz.fbs.kern.testhulp.REST_CLIENT_LOGGING
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

        // Negen cijfers passen op de spec-regex en vallen pas bij de elfproef af.
        val verwacht = if (waarde.length == 9 && waarde.all(Char::isDigit)) null else "getBerichten.arg0 (Pattern)"

        assertAlleenIdEnVeld(antwoord, log, waarde, verwacht)
    }

    @Test
    fun `de categorieën die invoer loggen, staan vast op INFO, ook voor een DEBUG op de categorie zelf`() {
        val config = ConfigProvider.getConfig()

        (QUARKUS_REST_MET_INVOER + REST_CLIENT_LOGGING).forEach { categorie ->
            listOf("level", "min-level").forEach { sleutel ->
                assertEquals("INFO", config.getValue("quarkus.log.category.\"$categorie\".$sleutel", String::class.java), "$categorie $sleutel")
            }
        }

        val gemaskeerd = config.getValue("quarkus.rest-client.logging.masked-headers", String::class.java).split(',')

        assertTrue(gemaskeerd.containsAll(listOf("X-Ontvanger", "X-Sessie")), gemaskeerd.toString())
    }

    /**
     * De klem werkt alleen als hij de categorieën noemt waar Quarkus werkelijk logt; een nieuwe versie
     * kan er een hernoemen of toevoegen. Deze canary zet het framework open (de test-JVM past de
     * klem niet toe) en eist dat elke regel met een stuk van de waarde uit een geklemde categorie komt.
     */
    @Test
    fun `alleen geklemde categorieën van het framework citeren de invoer`() {
        val waarde = "99999365x"

        val bronnen = Logvanger(open = FRAMEWORK_WORTELS).use { logvanger ->
            listOf("\"BSN:$waarde\"", """{"type": "$waarde", "waarde": "999993653"}""", """{"type": "BSN", "waarde": x$waarde}""")
                .forEach { ontvanger ->
                    given().contentType(ContentType.JSON)
                        .body("""{"afzender": "$AFZENDER", "ontvanger": $ontvanger, "onderwerp": "o", "inhoud": "i"}""")
                        .`when`().post("$BASIS/aanleveringen")
                }

            given().header("X-Ontvanger", "KVK:90000001").`when`().get("$BASIS/berichten?page=$waarde")

            logvanger.bronnenVan(herkenbareDelen(waarde))
        }

        assertTrue(bronnen.isNotEmpty(), "de canary lokt niets uit en bewijst dus niets")
        assertTrue(QUARKUS_REST_MET_INVOER.containsAll(bronnen), "ongeklemde categorieën citeren de invoer: ${bronnen - QUARKUS_REST_MET_INVOER.toSet()}")
        assertTrue("org.jboss.resteasy.reactive.server.handlers.ParameterHandler" in bronnen, "de parameter-route lokt niets uit: $bronnen")
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
        private const val AFZENDER = "00000001003214345000"
        private const val BASIS = "/api/v1"

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
