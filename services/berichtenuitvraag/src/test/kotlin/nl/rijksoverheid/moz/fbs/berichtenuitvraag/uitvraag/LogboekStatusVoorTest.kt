package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.opentelemetry.api.trace.StatusCode
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.EventType
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pint de LDV-status-mapping (AVG art. 30-audittrail) per event-type: een refactor
 * van de SSE-resource mag een mislukte of deels mislukte ophaling niet stil als
 * OK in het verwerkingenlogboek laten belanden.
 */
class LogboekStatusVoorTest {

    @Test
    fun `volledig geslaagde ophaling logt OK`() {
        val gereed = MagazijnEvent(
            event = EventType.OPHALEN_GEREED,
            totaalBerichten = 2,
            geslaagd = 2,
            mislukt = 0,
            totaalMagazijnen = 2,
        )

        assertEquals(StatusCode.OK, logboekStatusVoor(gereed))
    }

    @Test
    fun `partial failure (mislukt groter dan 0) logt ERROR`() {
        val deelsMislukt = MagazijnEvent(
            event = EventType.OPHALEN_GEREED,
            totaalBerichten = 1,
            geslaagd = 1,
            mislukt = 1,
            totaalMagazijnen = 2,
        )

        assertEquals(StatusCode.ERROR, logboekStatusVoor(deelsMislukt))
    }

    @Test
    fun `OPHALEN_FOUT logt ERROR`() {
        val fout = MagazijnEvent(
            event = EventType.OPHALEN_FOUT,
            totaalMagazijnen = 0,
            foutmelding = "Interne fout bij opslaan resultaten",
        )

        assertEquals(StatusCode.ERROR, logboekStatusVoor(fout))
    }

    @Test
    fun `tussentijdse events wijzigen de status niet`() {
        assertNull(logboekStatusVoor(MagazijnEvent(event = EventType.MAGAZIJN_BEVRAGING_GESTART, magazijnId = "magazijn-a")))
        assertNull(
            logboekStatusVoor(
                MagazijnEvent(
                    event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID,
                    magazijnId = "magazijn-a",
                    status = MagazijnStatus.FOUT,
                ),
            ),
        )
    }
}
