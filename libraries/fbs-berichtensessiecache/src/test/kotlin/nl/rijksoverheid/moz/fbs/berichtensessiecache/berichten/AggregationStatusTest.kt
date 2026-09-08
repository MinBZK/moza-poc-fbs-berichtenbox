package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
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
    fun `negatief nietOpgehaald wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            AggregationStatus(totaalMagazijnen = 2, nietOpgehaald = -1)
        }
        assertEquals("nietOpgehaald mag niet negatief zijn", ex.message)
    }

    /**
     * De drie tellers verdelen samen alle organisaties van de ronde; elke combinatie die er méér
     * verdeelt dan er zijn, is een boekhoudfout. Ook de tak waarin alleen `nietOpgehaald` over de
     * grens duwt: die zou anders pas opvallen als de som van de andere twee al te hoog was.
     */
    @ParameterizedTest
    @CsvSource("2, 1, 0", "0, 2, 1", "0, 0, 3", "1, 1, 1")
    fun `de tellers samen groter dan totaalMagazijnen wordt geweigerd`(geslaagd: Int, mislukt: Int, nietOpgehaald: Int) {
        val ex = assertThrows<IllegalArgumentException> {
            AggregationStatus(
                totaalMagazijnen = 2,
                geslaagd = geslaagd,
                mislukt = mislukt,
                nietOpgehaald = nietOpgehaald,
            )
        }
        assertEquals("geslaagd + mislukt + nietOpgehaald mag niet groter zijn dan totaalMagazijnen", ex.message)
    }

    @Test
    fun `geldige status met alle drie de uitkomsten`() {
        val status = AggregationStatus(
            status = OphalenStatus.GEREED,
            totaalMagazijnen = 3,
            geslaagd = 1,
            mislukt = 1,
            nietOpgehaald = 1,
        )
        assertEquals(1, status.nietOpgehaald)
    }

    /** Een status die vóór de derde teller is weggeschreven, leest terug met nietOpgehaald 0. */
    @Test
    fun `nietOpgehaald is optioneel`() {
        val status = AggregationStatus(totaalMagazijnen = 2, geslaagd = 2)

        assertEquals(0, status.nietOpgehaald)
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
