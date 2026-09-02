package nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * De verdeling van gedrag over de honderd magazijnen.
 *
 * Deze getallen zijn geen implementatiedetail maar een afspraak: ze bepalen wat een stakeholder in
 * een demo ziet. Verschuift er iets — een magazijn dat traag hoort te zijn valt ineens helemaal uit
 * — dan verandert het verhaal bij de demo zonder dat iemand dat merkt.
 */
class GedragVerdelingTest {

    @ParameterizedTest
    @CsvSource(
        // De vaste uitzonderingen.
        "28,UIT", "97,UIT",
        "33,STUK", "66,STUK", "98,STUK",
        "22,WEIGERT",
        "71,MALFORMED",
        // Veelvouden van twintig haperen…
        "20,HAPERT", "40,HAPERT", "60,HAPERT", "80,HAPERT",
        // …en veelvouden van vijf die dat niet zijn, zijn traag.
        "5,TRAAG", "10,TRAAG", "15,TRAAG", "95,TRAAG",
        // De rest doet het gewoon.
        "1,NORMAAL", "2,NORMAAL", "51,NORMAAL", "99,NORMAAL",
    )
    fun `het volgnummer bepaalt de modus`(index: Int, verwacht: String) {
        assertEquals(GedragModus.valueOf(verwacht), GedragVerdeling.voorIndex(index).modus)
    }

    /**
     * De volgorde van de regels telt. Index 20 is een veelvoud van zowel twintig als vijf, en index
     * 100 zou zonder de voorrang van de vaste uitzonderingen ook ergens anders belanden. Zonder deze
     * test is die volgorde met één herschikking stilzwijgend om te draaien.
     */
    @ParameterizedTest
    @CsvSource("20,HAPERT", "40,HAPERT", "60,HAPERT", "80,HAPERT")
    fun `een veelvoud van twintig hapert en is niet traag`(index: Int, verwacht: String) {
        assertEquals(GedragModus.valueOf(verwacht), GedragVerdeling.voorIndex(index).modus)
    }

    /**
     * De aandelen over de volle set. Veruit de meeste organisaties doen het gewoon, een handvol is
     * traag, een paar liggen eruit — dat is het beeld dat de demo hoort te geven.
     */
    @Test
    fun `de verdeling over 98 magazijnen klopt met de afspraak`() {
        val telling = (1..AANTAL_GESIMULEERD)
            .map { GedragVerdeling.voorIndex(it).modus }
            .groupingBy { it }
            .eachCount()

        assertEquals(
            mapOf(
                GedragModus.NORMAAL to 72,
                GedragModus.TRAAG to 15,
                GedragModus.HAPERT to 4,
                GedragModus.STUK to 3,
                GedragModus.UIT to 2,
                GedragModus.WEIGERT to 1,
                GedragModus.MALFORMED to 1,
            ),
            telling.toSortedMap(),
        )
    }

    /**
     * Waar de twee bijzondere storingen staan, is een keuze: de weigering valt binnen de persona met
     * 45 organisaties, het onbruikbare antwoord alleen binnen de grootste. Zo houden de twee kleinste
     * persona's een schoon contrast.
     */
    @Test
    fun `de twee bijzondere storingen staan binnen de bedoelde persona`() {
        assertEquals(GedragModus.WEIGERT, GedragVerdeling.voorIndex(22).modus)
        assertEquals(GedragModus.MALFORMED, GedragVerdeling.voorIndex(71).modus)
    }

    @Test
    fun `een volgnummer onder één is een rekenfout en geen haperend magazijn`() {
        assertThrows<IllegalArgumentException> { GedragVerdeling.voorIndex(0) }
        assertThrows<IllegalArgumentException> { GedragVerdeling.voorIndex(-1) }
    }

    @Test
    fun `traag betekent traag en niet onbereikbaar`() {
        val traag = GedragVerdeling.voorIndex(5)

        assertEquals(true, traag.latencyP95Ms > traag.latencyP50Ms, "een lange staart hoort erbij")
        assertEquals(true, traag.latencyP95Ms < UITVRAAG_TIMEOUT_MS, "traag mag niet in een timeout lopen")
    }

    @Test
    fun `onbereikbaar duurt langer dan de aanroeper wil wachten`() {
        assertEquals(true, GedragVerdeling.voorIndex(28).latencyP50Ms > UITVRAAG_TIMEOUT_MS)
    }

    private companion object {
        const val AANTAL_GESIMULEERD = 98

        /** De query-timeout die de uitvraag per magazijn hanteert. */
        const val UITVRAAG_TIMEOUT_MS = 10_000
    }
}
