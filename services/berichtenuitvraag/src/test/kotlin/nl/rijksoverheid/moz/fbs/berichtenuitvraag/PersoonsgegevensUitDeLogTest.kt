package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AanmeldMockProfile
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
import java.util.UUID

/**
 * Een magazijn dat een aanmelding met een foute ontvanger stuurt, of een portaal dat een sessie
 * start met een foute `X-Ontvanger`, krijgt een 400 — en mag daarmee geen stuk van het BSN in de
 * applicatielog zetten, op geen enkel niveau. De melding van Jackson citeert de invoer; de log noemt
 * daarom alleen het id, het type, het veld en de positie.
 *
 * Elke waarde valt op een andere plek af: met een letter erin bij de spec-regex, te kort bij de
 * lengtecontrole, negen cijfers bij de elfproef. Waar een domeininvariant de waarde afwijst, is er
 * geen veld om te noemen; die gevallen bewaken dat de log ook daar niets van de waarde overneemt.
 */
@QuarkusTest
@TestProfile(AanmeldMockProfile::class)
class PersoonsgegevensUitDeLogTest {

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("aanmeldingen")
    fun `een aanmelding met een foute ontvanger zet geen stuk ervan in de log`(
        geval: String,
        waarde: String,
        ontvanger: String,
        verwachtInLog: String?,
    ) {
        val (antwoord, log) = metLog {
            given()
                .contentType("application/cloudevents+json")
                .body(aanmelding(ontvanger))
                .`when`().post("/api/v1/aanmeldingen")
                .then()
                .extract()
        }

        assertAlleenIdEnVeld(antwoord, log, waarde, verwachtInLog)
    }

    @ParameterizedTest
    @ValueSource(strings = ["99999365", "99999365x", "999993654"])
    fun `een sessiestart met een foute X-Ontvanger zet geen stuk ervan in de log`(waarde: String) {
        val (antwoord, log) = metLog {
            given()
                .contentType("application/json")
                .header("X-Ontvanger", "BSN:$waarde")
                .`when`().post("/api/v1/sessies")
                .then()
                .extract()
        }

        // De spec-regex vraagt hier alleen cijfers; lengte en elfproef zijn domeininvarianten.
        val verwacht = if (waarde.all(Char::isDigit)) null else "startSessie.arg0 (Pattern)"

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
            listOf("\"BSN:$waarde\"", """{"type": "BSN", "waarde": x$waarde}""").forEach { ontvanger ->
                given().contentType("application/cloudevents+json").body(aanmelding(ontvanger)).`when`().post("/api/v1/aanmeldingen")
            }

            logvanger.bronnenVan(herkenbareDelen(waarde))
        }

        assertTrue(bronnen.isNotEmpty(), "de canary lokt niets uit en bewijst dus niets")
        assertTrue(QUARKUS_REST_MET_INVOER.containsAll(bronnen), "ongeklemde categorieën citeren de invoer: ${bronnen - QUARKUS_REST_MET_INVOER.toSet()}")
    }

    private fun aanmelding(ontvanger: String): String = """
        {
          "id": "evt-${UUID.randomUUID()}",
          "source": "urn:nld:oin:$AFZENDER:systeem:fbs-magazijn",
          "specversion": "1.0",
          "type": "nl.rijksoverheid.fbs.bericht.gepubliceerd",
          "subject": "${UUID.randomUUID()}",
          "time": "2026-06-04T10:00:00Z",
          "datacontenttype": "application/json",
          "dataschema": "https://schemas.fbs.rijksoverheid.nl/bericht-gepubliceerd/v1",
          "data": {
            "berichtId": "${UUID.randomUUID()}",
            "afzender": "$AFZENDER",
            "ontvanger": $ontvanger,
            "onderwerp": "Onderwerp",
            "tijdstipOntvangst": "2026-06-04T09:59:00Z",
            "publicatietijdstip": "2026-06-04T10:00:00Z"
          }
        }
    """.trimIndent()

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
        /** De Magazijn-A-sleutel uit het `%test`-magazijnregister; een onbekende bron geeft al eerder 400. */
        private const val AFZENDER = "00000000000000100000"

        @JvmStatic
        fun aanmeldingen() = listOf("99999365", "99999365x", "999993654").flatMap { waarde ->
            // "Als tekst" is de vorm uit de header in de body; Jackson citeert de tekst letterlijk.
            // "Onleesbaar" begint met een letter: alleen dan noemt Jackson het hele token. Een
            // ongeldige waarde wijst het domeinobject af, als cause van de 400; dat noemt geen veld.
            // Het type is in dit model tekst, en de aanmeldservice toetst het zelf met een vaste melding.
            listOf(
                Arguments.of("als tekst", waarde, "\"BSN:$waarde\"", "veld=data.ontvanger,"),
                Arguments.of("als waarde", waarde, """{"type": "BSN", "waarde": "$waarde"}""", "cause=DomainValidationException"),
                Arguments.of("als type", waarde, """{"type": "$waarde", "waarde": "999990019"}""", null),
                Arguments.of("als lijst", waarde, """{"type": "BSN", "waarde": ["$waarde"]}""", "veld=data.ontvanger.waarde,"),
                Arguments.of("onleesbaar", waarde, """{"type": "BSN", "waarde": x$waarde}""", "positie=13:"),
            )
        }
    }
}
