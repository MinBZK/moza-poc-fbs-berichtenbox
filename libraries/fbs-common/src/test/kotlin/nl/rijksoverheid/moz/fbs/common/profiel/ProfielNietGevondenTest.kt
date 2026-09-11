package nl.rijksoverheid.moz.fbs.common.profiel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
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

        assertEquals(Profiel404Duiding.PartijZonderProfiel, ProfielNietGevonden.duid(body))
    }

    @Test
    fun `omringende witruimte en afwijkende hoofdletters blijven herkenbaar`() {
        assertEquals(
            Profiel404Duiding.PartijZonderProfiel,
            ProfielNietGevonden.duid("""{"title":"  partij NIET gevonden  "}"""),
        )
    }

    @Test
    fun `een antwoord dat zichzelf niet als 404 aankondigt telt niet als opt-out`() {
        // Zonder deze controle zou een 200 of 500 die toevallig dezelfde title draagt de
        // berichtenbox stil leegmaken.
        assertStoring("""{"title":"Partij niet gevonden","status":500}""")
    }

    @Test
    fun `een ontbrekende status laat de titel beslissen`() {
        // `status` is optioneel in RFC 9457; een problem-body zonder dat veld mag niet
        // daarom al als storing gelden.
        assertEquals(
            Profiel404Duiding.PartijZonderProfiel,
            ProfielNietGevonden.duid("""{"title":"Partij niet gevonden"}"""),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // Andere 404-gevallen van dezelfde dienst: horen géén opt-out te zijn.
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
    fun `elk ander 404-antwoord telt als storing`(body: String) {
        assertStoring(body)
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "   ", "\n"])
    fun `een ontbrekend of leeg antwoord telt als storing`(body: String?) {
        assertStoring(body)
    }

    @Test
    fun `een body boven de bovengrens telt als storing en wordt niet geparsed`() {
        // Een defecte upstream die een foutpagina van megabytes teruggeeft mag niet als
        // JSON-boom op de heap belanden.
        val teGroot = "{\"title\":\"Partij niet gevonden\",\"vulling\":\"" +
            "x".repeat(ProfielNietGevonden.MAX_BODY_TEKENS) + "\"}"

        val duiding = assertStoring(teGroot)

        assertTrue(
            duiding.omschrijving.contains("te groot"),
            "omschrijving moet de reden benoemen — gevonden: ${duiding.omschrijving}",
        )
    }

    @Test
    fun `de omschrijving draagt de titel van het afwijkende antwoord`() {
        val duiding = assertStoring("""{"title":"Voorkeur niet gevonden","status":404}""")

        assertTrue(
            duiding.omschrijving.contains("Voorkeur niet gevonden"),
            "beheer moet aan de omschrijving zien wélk antwoord het was — gevonden: ${duiding.omschrijving}",
        )
    }

    @Test
    fun `een titel met control-chars komt gesaniteerd en afgekapt in de omschrijving`() {
        // De titel komt ongevalideerd van buiten; een CRLF erin zou een tweede logregel
        // kunnen vervalsen.
        val duiding = assertStoring("""{"title":"Ramp\r\nERROR: nep","status":404}""")

        assertFalse(duiding.omschrijving.contains("\n"), "geen regeleinde in de omschrijving")
        assertFalse(duiding.omschrijving.contains("\r"), "geen carriage return in de omschrijving")
    }

    @Test
    fun `een extreem lange titel wordt afgekapt`() {
        val duiding = assertStoring("""{"title":"${"A".repeat(500)}","status":404}""")

        assertTrue(
            duiding.omschrijving.length < 200,
            "omschrijving moet begrensd zijn — lengte ${duiding.omschrijving.length}",
        )
    }

    private fun assertStoring(body: String?): Profiel404Duiding.Storing =
        assertInstanceOf(Profiel404Duiding.Storing::class.java, ProfielNietGevonden.duid(body))
}
