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

    @Test
    fun `meer niet-geleverde organisaties dan mislukt plus nietOpgehaald wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            AggregationStatus(
                totaalMagazijnen = 2,
                mislukt = 1,
                nietGeleverd = listOf(
                    NietGeleverd("magazijn-a", "A", MagazijnFoutStatus.FOUT),
                    NietGeleverd("magazijn-b", "B", MagazijnFoutStatus.TIMEOUT),
                ),
            )
        }

        assertEquals("nietGeleverd mag niet meer organisaties noemen dan mislukt + nietOpgehaald", ex.message)
    }

    /** Een status uit Redis van vóór dit veld draagt de tellers maar niet de lijst; die moet leesbaar blijven. */
    @Test
    fun `minder niet-geleverde organisaties dan de tellers is toegestaan`() {
        val status = AggregationStatus(totaalMagazijnen = 2, mislukt = 1, nietOpgehaald = 1)

        assertEquals(emptyList<NietGeleverd>(), status.nietGeleverd)
    }

    @Test
    fun `een organisatie die dubbel als niet-geleverd staat wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            AggregationStatus(
                totaalMagazijnen = 2,
                mislukt = 2,
                nietGeleverd = List(2) { NietGeleverd("magazijn-a", "A", MagazijnFoutStatus.FOUT) },
            )
        }

        assertEquals("nietGeleverd noemt een organisatie dubbel", ex.message)
    }

    /**
     * Volledig is `aantalNietGeleverd == 0`, niet een lege lijst: een status zonder namen (van vóór
     * dat ze bewaard werden) mag niet als volledig lezen.
     */
    @ParameterizedTest(name = "mislukt={0}, nietOpgehaald={1}, namen={2}")
    @CsvSource("0, 0, 0", "1, 0, 0", "1, 1, 1", "1, 1, 2")
    fun `de volledigheid telt wie niet leverde, ook zonder namen`(mislukt: Int, nietOpgehaald: Int, namen: Int) {
        val nietGeleverd = (0 until namen).map { NietGeleverd("magazijn-$it", "Organisatie $it", MagazijnFoutStatus.FOUT) }
        val status = AggregationStatus(
            totaalMagazijnen = 3,
            mislukt = mislukt,
            nietOpgehaald = nietOpgehaald,
            nietGeleverd = nietGeleverd,
        )

        assertEquals(Volledigheid(mislukt + nietOpgehaald, nietGeleverd), status.volledigheid())
    }
}
