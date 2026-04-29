package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MagazijnEventTest {

    @Test
    fun `MAGAZIJN_BEVRAGING_GESTART zonder magazijnId gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnEvent(event = EventType.MAGAZIJN_BEVRAGING_GESTART)
        }
        assertEquals("MAGAZIJN_BEVRAGING_GESTART vereist magazijnId", ex.message)
    }

    @Test
    fun `MAGAZIJN_BEVRAGING_VOLTOOID zonder magazijnId gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnEvent(event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID, status = MagazijnStatus.OK)
        }
        assertEquals("MAGAZIJN_BEVRAGING_VOLTOOID vereist magazijnId", ex.message)
    }

    @Test
    fun `MAGAZIJN_BEVRAGING_VOLTOOID zonder status gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnEvent(event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID, magazijnId = "magazijn-a")
        }
        assertEquals("MAGAZIJN_BEVRAGING_VOLTOOID vereist status", ex.message)
    }

    @Test
    fun `OPHALEN_GEREED zonder totaalBerichten gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnEvent(event = EventType.OPHALEN_GEREED, totaalMagazijnen = 2)
        }
        assertEquals("OPHALEN_GEREED vereist totaalBerichten", ex.message)
    }

    @Test
    fun `OPHALEN_GEREED zonder totaalMagazijnen gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnEvent(event = EventType.OPHALEN_GEREED, totaalBerichten = 5)
        }
        assertEquals("OPHALEN_GEREED vereist totaalMagazijnen", ex.message)
    }

    @Test
    fun `OPHALEN_FOUT zonder foutmelding gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnEvent(event = EventType.OPHALEN_FOUT, totaalMagazijnen = 2)
        }
        assertEquals("OPHALEN_FOUT vereist foutmelding", ex.message)
    }

    @Test
    fun `OPHALEN_FOUT zonder totaalMagazijnen gooit IllegalArgumentException`() {
        val ex = assertThrows<IllegalArgumentException> {
            MagazijnEvent(event = EventType.OPHALEN_FOUT, foutmelding = "Fout")
        }
        assertEquals("OPHALEN_FOUT vereist totaalMagazijnen", ex.message)
    }

    @Test
    fun `geldige MAGAZIJN_BEVRAGING_GESTART slaagt`() {
        val event = MagazijnEvent(
            event = EventType.MAGAZIJN_BEVRAGING_GESTART,
            magazijnId = "magazijn-a",
            naam = "Magazijn A",
        )
        assertEquals(EventType.MAGAZIJN_BEVRAGING_GESTART, event.event)
        assertEquals("magazijn-a", event.magazijnId)
    }

    @Test
    fun `geldige MAGAZIJN_BEVRAGING_VOLTOOID slaagt`() {
        val event = MagazijnEvent(
            event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID,
            magazijnId = "magazijn-a",
            naam = "Magazijn A",
            status = MagazijnStatus.OK,
            aantalBerichten = 3,
        )
        assertEquals(EventType.MAGAZIJN_BEVRAGING_VOLTOOID, event.event)
        assertEquals(MagazijnStatus.OK, event.status)
        assertEquals(3, event.aantalBerichten)
    }

    @Test
    fun `geldige OPHALEN_GEREED slaagt`() {
        val event = MagazijnEvent(
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
        val event = MagazijnEvent(
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
