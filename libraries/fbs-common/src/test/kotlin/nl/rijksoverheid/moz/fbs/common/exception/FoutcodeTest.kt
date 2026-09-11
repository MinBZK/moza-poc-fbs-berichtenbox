package nl.rijksoverheid.moz.fbs.common.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI

class FoutcodeTest {

    @Test
    fun `uri is de code onder de fbs-fout-namespace`() {
        assertEquals(URI.create("urn:fbs:fout:bericht-verwijderd"), Foutcode.BERICHT_VERWIJDERD.uri)
    }

    /**
     * De codewaarden zijn contract naar afnemers buiten dit repo: zij hangen er gedrag aan. Een
     * hernoeming compileert en houdt elke andere test groen, dus die verschuiving hoort hier een
     * bewuste, in review zichtbare handeling te zijn. Een nieuwe code toevoegen mag; deze lijst
     * groeit dan mee.
     */
    @Test
    fun `de codewaarden liggen vast`() {
        val verwacht = mapOf(
            Foutcode.BERICHT_ONBEKEND to "bericht-onbekend",
            Foutcode.BERICHT_VERWIJDERD to "bericht-verwijderd",
            Foutcode.NOG_NIET_OPGEHAALD to "nog-niet-opgehaald",
            Foutcode.OPHALEN_BEZIG to "ophalen-bezig",
            Foutcode.OPHALEN_MISLUKT to "ophalen-mislukt",
            Foutcode.TIJDELIJK_NIET_BESCHIKBAAR to "tijdelijk-niet-beschikbaar",
            Foutcode.GEEN_ACTIEVE_SESSIE to "geen-actieve-sessie",
            Foutcode.NIET_GEVONDEN to "niet-gevonden",
            Foutcode.ONGELDIG_VERZOEK to "ongeldig-verzoek",
            Foutcode.GEEN_TOEGANG to "geen-toegang",
            Foutcode.CONFLICT to "conflict",
            Foutcode.KETEN_FOUT to "keten-fout",
            Foutcode.CONFIGURATIE_MISMATCH to "configuratie-mismatch",
            Foutcode.INTERNE_FOUT to "interne-fout",
        )

        assertEquals(verwacht, Foutcode.entries.associateWith { it.code })
    }

    @Test
    fun `codes zijn onderling uniek`() {
        // Twee entries met dezelfde code zouden twee situaties onder één kenmerk schuiven —
        // precies wat deze enum bestaat om te voorkomen.
        assertEquals(Foutcode.entries.size, Foutcode.entries.map { it.code }.toSet().size)
    }

    @ParameterizedTest
    @EnumSource(Foutcode::class)
    fun `elke code draagt een uitleg die aan een gebruiker getoond kan worden`(foutcode: Foutcode) {
        // De uitleg landt als `detail` op een 5xx, waar het gemaskeerde standaarddetail anders
        // iets anders zou zeggen dan het kenmerk. Leeg zou dat gat stil terugbrengen.
        assertTrue(foutcode.uitleg.isNotBlank(), "${foutcode.code} heeft geen uitleg")
    }

    @ParameterizedTest
    @EnumSource(Foutcode::class)
    fun `elke code levert een absolute uri die Problem accepteert`(foutcode: Foutcode) {
        assertTrue(foutcode.uri.isAbsolute, "${foutcode.uri} moet absoluut zijn")

        // Problem.of clamp-t een niet-absolute type stilzwijgend naar about:blank; die
        // stille terugval zou het kenmerk uit elk antwoord laten verdwijnen.
        val problem = Problem.of(title = "Error", status = 400, type = foutcode.uri)

        assertEquals(foutcode.uri, problem.type)
        assertNotEquals(Problem.ABOUT_BLANK, problem.type)
    }

    @ParameterizedTest
    @CsvSource(
        "400, ONGELDIG_VERZOEK",
        "401, GEEN_TOEGANG",
        "403, GEEN_TOEGANG",
        "404, NIET_GEVONDEN",
        "409, CONFLICT",
        "410, BERICHT_VERWIJDERD",
        "415, ONGELDIG_VERZOEK",
        "422, ONGELDIG_VERZOEK",
        "429, TIJDELIJK_NIET_BESCHIKBAAR",
        "500, INTERNE_FOUT",
        "502, KETEN_FOUT",
        "503, TIJDELIJK_NIET_BESCHIKBAAR",
        "504, KETEN_FOUT",
    )
    fun `voorStatus levert de terugval die bij de status hoort`(status: Int, verwacht: Foutcode) {
        assertEquals(verwacht, Foutcode.voorStatus(status))
    }

    @Test
    fun `voorStatus claimt nooit uit zichzelf dat een bericht onbekend is`() {
        val terugvallen = (100..599).map { Foutcode.voorStatus(it) }.toSet()

        assertTrue(Foutcode.BERICHT_ONBEKEND !in terugvallen, "terugval koos $terugvallen")
    }
}
