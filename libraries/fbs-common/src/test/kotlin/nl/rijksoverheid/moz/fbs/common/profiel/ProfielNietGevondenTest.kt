package nl.rijksoverheid.moz.fbs.common.profiel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource

class ProfielNietGevondenTest {

    @Test
    fun `het problem-antwoord van de Profiel-service voor een onbekende partij wordt herkend`() {
        val body = """
            {
              "type": "about:blank",
              "title": "Partij niet gevonden",
              "status": 404,
              "detail": "Geen partij gevonden voor het opgegeven identificatienummer."
            }
        """.trimIndent()

        assertTrue(ProfielNietGevonden.isPartijZonderProfiel(body))
    }

    @Test
    fun `omringende witruimte en afwijkende hoofdletters blijven herkenbaar`() {
        assertTrue(ProfielNietGevonden.isPartijZonderProfiel("""{"title":"  partij NIET gevonden  "}"""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // Een ander 404-geval van dezelfde dienst: hoort géén opt-out te zijn.
            """{"type":"about:blank","title":"Contactgegeven niet gevonden","status":404}""",
            """{"type":"about:blank","title":"Voorkeur niet gevonden","status":404}""",
            // Problem-body zonder title, of met een title die geen tekst is.
            """{"type":"about:blank","status":404}""",
            """{"title":404}""",
            """{"title":null}""",
            // Wat een routing- of infra-404 oplevert: geen JSON, of geen JSON-object.
            "<html><body>Not Found</body></html>",
            "Not Found",
            """["Partij niet gevonden"]""",
            """"Partij niet gevonden"""",
            "{",
        ],
    )
    fun `elk ander 404-antwoord telt niet als partij zonder profiel`(body: String) {
        assertFalse(ProfielNietGevonden.isPartijZonderProfiel(body))
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["   ", "\n"])
    fun `een ontbrekend of leeg antwoord telt niet als partij zonder profiel`(body: String?) {
        assertFalse(ProfielNietGevonden.isPartijZonderProfiel(body))
    }
}
