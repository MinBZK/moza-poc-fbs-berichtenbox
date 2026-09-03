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
        "500, INTERNE_FOUT",
        "502, KETEN_FOUT",
        "503, TIJDELIJK_NIET_BESCHIKBAAR",
        "504, INTERNE_FOUT",
    )
    fun `voorStatus levert de terugval die bij de status hoort`(status: Int, verwacht: Foutcode) {
        assertEquals(verwacht, Foutcode.voorStatus(status))
    }

    @Test
    fun `voorStatus claimt nooit uit zichzelf dat een bericht onbekend is`() {
        // Een 404 op een onbekend pad is geen onbekend bericht; die terugval zou de afnemer een
        // uitspraak over een bericht laten doen die er niet is. Voor 410 ligt dat anders: geen
        // framework-pad produceert die status, dus komt hij altijd van de keten zelf.
        val terugvallen = (100..599).map { Foutcode.voorStatus(it) }.toSet()

        assertTrue(Foutcode.BERICHT_ONBEKEND !in terugvallen, "terugval koos $terugvallen")
    }
}
