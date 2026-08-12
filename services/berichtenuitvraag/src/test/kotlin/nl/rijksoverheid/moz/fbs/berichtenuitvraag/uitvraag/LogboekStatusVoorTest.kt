package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.opentelemetry.api.trace.StatusCode
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingGeslaagd
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingGestart
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingMislukt
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnFoutStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenGereed
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenMislukt
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OpslaanMislukt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Pint de LDV-status-mapping (AVG art. 30-audittrail) per event-type: een refactor
 * van de SSE-resource mag een mislukte of deels mislukte ophaling niet stil als
 * OK in het verwerkingenlogboek laten belanden.
 */
class LogboekStatusVoorTest {

    companion object {
        /** Alle soorten die géén eindstatus zetten — de audittrail mag pas op het finale event kantelen. */
        @JvmStatic
        fun tussentijdseEvents(): List<MagazijnEvent> = listOf(
            MagazijnBevragingGestart(magazijnId = "00000001001234567890", naam = "Magazijn A"),
            MagazijnBevragingGestart(magazijnId = "00000001001234567890", naam = null),
            MagazijnBevragingGeslaagd(magazijnId = "00000001001234567890", naam = "Magazijn A", aantalBerichten = 3),
            MagazijnBevragingMislukt(
                magazijnId = "00000001001234567890",
                naam = "Magazijn A",
                status = MagazijnFoutStatus.FOUT,
                foutmelding = "Magazijn tijdelijk niet bereikbaar",
            ),
            MagazijnBevragingMislukt(
                magazijnId = "00000001001234567890",
                naam = "Magazijn A",
                status = MagazijnFoutStatus.TIMEOUT,
                foutmelding = "Magazijn reageerde niet binnen de timeout",
            ),
        )

        /** Elk fout-eindbericht logt ERROR, ongeacht of het ophalen of het opslaan strandde. */
        @JvmStatic
        fun fouteindEvents(): List<MagazijnEvent> = listOf(
            OphalenMislukt(foutmelding = "Interne fout bij opslaan resultaten (ref: abc)", referentie = "abc"),
            OpslaanMislukt(
                foutmelding = "Resultaten konden niet worden opgeslagen (ref: abc)",
                geslaagd = 1,
                mislukt = 1,
                totaalMagazijnen = 2,
                referentie = "abc",
            ),
        )
    }

    @Test
    fun `volledig geslaagde ophaling logt OK`() {
        val gereed = OphalenGereed(totaalBerichten = 2, geslaagd = 2, mislukt = 0, totaalMagazijnen = 2)

        assertEquals(StatusCode.OK, logboekStatusVoor(gereed))
    }

    @Test
    fun `ophaling zonder magazijnen logt OK`() {
        val leeg = OphalenGereed(totaalBerichten = 0, geslaagd = 0, mislukt = 0, totaalMagazijnen = 0)

        assertEquals(StatusCode.OK, logboekStatusVoor(leeg))
    }

    @Test
    fun `partial failure (mislukt groter dan 0) logt ERROR`() {
        val deelsMislukt = OphalenGereed(totaalBerichten = 1, geslaagd = 1, mislukt = 1, totaalMagazijnen = 2)

        assertEquals(StatusCode.ERROR, logboekStatusVoor(deelsMislukt))
    }

    @Test
    fun `volledig mislukte ophaling logt ERROR`() {
        val allesMislukt = OphalenGereed(totaalBerichten = 0, geslaagd = 0, mislukt = 2, totaalMagazijnen = 2)

        assertEquals(StatusCode.ERROR, logboekStatusVoor(allesMislukt))
    }

    @ParameterizedTest
    @MethodSource("fouteindEvents")
    fun `elk fout-eindbericht logt ERROR`(event: MagazijnEvent) {
        assertEquals(StatusCode.ERROR, logboekStatusVoor(event))
    }

    @ParameterizedTest
    @MethodSource("tussentijdseEvents")
    fun `tussentijdse events wijzigen de status niet`(event: MagazijnEvent) {
        assertNull(logboekStatusVoor(event))
    }
}
