package nl.rijksoverheid.moz.berichtensessiecache.berichten

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AggregationStatusTest {

    @Test
    fun `negatief totaalMagazijnen wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            AggregationStatus(totaalMagazijnen = -1)
        }
        assertEquals("totaalMagazijnen mag niet negatief zijn", ex.message)
    }

    @Test
    fun `negatief geslaagd wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            AggregationStatus(totaalMagazijnen = 2, geslaagd = -1)
        }
        assertEquals("geslaagd mag niet negatief zijn", ex.message)
    }

    @Test
    fun `negatief mislukt wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            AggregationStatus(totaalMagazijnen = 2, mislukt = -1)
        }
        assertEquals("mislukt mag niet negatief zijn", ex.message)
    }

    @Test
    fun `geslaagd + mislukt groter dan totaalMagazijnen wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            AggregationStatus(totaalMagazijnen = 2, geslaagd = 2, mislukt = 1)
        }
        assertEquals("geslaagd + mislukt mag niet groter zijn dan totaalMagazijnen", ex.message)
    }

    @Test
    fun `geldige status met alle magazijnen geslaagd`() {
        val status = AggregationStatus(
            status = OphalenStatus.GEREED,
            totaalMagazijnen = 3,
            geslaagd = 3,
            mislukt = 0,
        )
        assertEquals(3, status.geslaagd)
    }

    @Test
    fun `geldige status met deels mislukt`() {
        val status = AggregationStatus(
            status = OphalenStatus.GEREED,
            totaalMagazijnen = 3,
            geslaagd = 2,
            mislukt = 1,
        )
        assertEquals(2, status.geslaagd)
        assertEquals(1, status.mislukt)
    }

    @Test
    fun `default waarden zijn geldig`() {
        val status = AggregationStatus()
        assertEquals(OphalenStatus.GEREED, status.status)
        assertEquals(0, status.totaalMagazijnen)
    }
}
