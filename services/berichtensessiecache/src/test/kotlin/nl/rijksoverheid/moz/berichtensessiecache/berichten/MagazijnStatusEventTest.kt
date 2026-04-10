package nl.rijksoverheid.moz.berichtensessiecache.berichten

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MagazijnStatusEventTest {

    @Test
    fun `MAGAZIJN_STATUS zonder magazijnId gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnStatusEvent(event = EventType.MAGAZIJN_STATUS, status = MagazijnStatus.OK)
        }
        assertEquals("MAGAZIJN_STATUS vereist magazijnId", ex.message)
    }

    @Test
    fun `MAGAZIJN_STATUS zonder status gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnStatusEvent(event = EventType.MAGAZIJN_STATUS, magazijnId = "magazijn-a")
        }
        assertEquals("MAGAZIJN_STATUS vereist status", ex.message)
    }

    @Test
    fun `OPHALEN_GEREED zonder totaalBerichten gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnStatusEvent(event = EventType.OPHALEN_GEREED, totaalMagazijnen = 2)
        }
        assertEquals("OPHALEN_GEREED vereist totaalBerichten", ex.message)
    }

    @Test
    fun `OPHALEN_GEREED zonder totaalMagazijnen gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnStatusEvent(event = EventType.OPHALEN_GEREED, totaalBerichten = 5)
        }
        assertEquals("OPHALEN_GEREED vereist totaalMagazijnen", ex.message)
    }

    @Test
    fun `OPHALEN_FOUT zonder foutmelding gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnStatusEvent(event = EventType.OPHALEN_FOUT, totaalMagazijnen = 2)
        }
        assertEquals("OPHALEN_FOUT vereist foutmelding", ex.message)
    }

    @Test
    fun `OPHALEN_FOUT zonder totaalMagazijnen gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnStatusEvent(event = EventType.OPHALEN_FOUT, foutmelding = "Fout")
        }
        assertEquals("OPHALEN_FOUT vereist totaalMagazijnen", ex.message)
    }

    @Test
    fun `geldige MAGAZIJN_STATUS slaagt`() {
        val event = MagazijnStatusEvent(
            event = EventType.MAGAZIJN_STATUS,
            magazijnId = "magazijn-a",
            naam = "Magazijn A",
            status = MagazijnStatus.OK,
            aantalBerichten = 3,
        )
        assertEquals(EventType.MAGAZIJN_STATUS, event.event)
        assertEquals("magazijn-a", event.magazijnId)
    }

    @Test
    fun `geldige OPHALEN_GEREED slaagt`() {
        val event = MagazijnStatusEvent(
            event = EventType.OPHALEN_GEREED,
            totaalBerichten = 5,
            geslaagd = 2,
            mislukt = 0,
            totaalMagazijnen = 2,
        )
        assertEquals(EventType.OPHALEN_GEREED, event.event)
        assertEquals(5, event.totaalBerichten)
    }

    @Test
    fun `geldige OPHALEN_FOUT slaagt`() {
        val event = MagazijnStatusEvent(
            event = EventType.OPHALEN_FOUT,
            foutmelding = "Interne fout",
            totaalMagazijnen = 2,
            geslaagd = 0,
            mislukt = 2,
        )
        assertEquals(EventType.OPHALEN_FOUT, event.event)
        assertEquals("Interne fout", event.foutmelding)
    }
}
