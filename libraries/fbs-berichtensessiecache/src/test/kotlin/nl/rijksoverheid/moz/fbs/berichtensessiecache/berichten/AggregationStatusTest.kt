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

    @Test
    fun `meer afgekapte organisaties dan geslaagd wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            AggregationStatus(
                totaalMagazijnen = 2,
                geslaagd = 1,
                afgekapt = listOf(Afgekapt("magazijn-a", "A", 500), Afgekapt("magazijn-b", "B", 500)),
            )
        }

        assertEquals("afgekapt mag niet meer organisaties noemen dan geslaagd", ex.message)
    }

    @Test
    fun `een organisatie die dubbel als afgekapt staat wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            AggregationStatus(
                totaalMagazijnen = 2,
                geslaagd = 2,
                afgekapt = List(2) { Afgekapt("magazijn-a", "A", 500) },
            )
        }

        assertEquals("afgekapt noemt een organisatie dubbel", ex.message)
    }

    @Test
    fun `een organisatie die zowel afgekapt als niet geleverd is wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            AggregationStatus(
                totaalMagazijnen = 2,
                geslaagd = 1,
                mislukt = 1,
                nietGeleverd = listOf(NietGeleverd("magazijn-a", "A", MagazijnFoutStatus.FOUT)),
                afgekapt = listOf(Afgekapt("magazijn-a", "A", 500)),
            )
        }

        assertEquals("een organisatie kan niet tegelijk afgekapt en niet geleverd zijn", ex.message)
    }

    /** Een status zonder het veld leest als een ronde waarin niemand afgekapt is. */
    @Test
    fun `afgekapt is optioneel`() {
        assertEquals(emptyList<Afgekapt>(), AggregationStatus(totaalMagazijnen = 1, geslaagd = 1).afgekapt)
    }

    @ParameterizedTest(name = "{0} afgekapt")
    @CsvSource("0", "1", "2")
    fun `de volledigheid draagt wie niet alles leverde`(aantal: Int) {
        val afgekapt = (0 until aantal).map { Afgekapt("magazijn-$it", "Organisatie $it", 500, 812L) }
        val nietGeleverd = listOf(NietGeleverd("magazijn-x", "X", MagazijnFoutStatus.TIMEOUT))
        val status = AggregationStatus(
            totaalMagazijnen = 3,
            geslaagd = 2,
            mislukt = 1,
            nietGeleverd = nietGeleverd,
            afgekapt = afgekapt,
        )

        assertEquals(Volledigheid(1, nietGeleverd, afgekapt), status.volledigheid())
    }

    @Test
    fun `een negatief aantal opgehaald wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> { Afgekapt("magazijn-a", "A", -1) }

        assertEquals("aantalOpgehaald mag niet negatief zijn", ex.message)
    }

    /** "500 van 500 — niet alles opgehaald" is een tegenspraak; het totaal moet erboven liggen. */
    @ParameterizedTest(name = "opgehaald={0}, totaal={1}")
    @CsvSource("500, 500", "500, 499", "0, 0")
    fun `een totaal dat niet boven het opgehaalde ligt wordt geweigerd`(opgehaald: Int, totaal: Long) {
        val ex = assertThrows<IllegalArgumentException> { Afgekapt("magazijn-a", "A", opgehaald, totaal) }

        assertEquals("totaalBeschikbaar moet groter zijn dan aantalOpgehaald, anders is er niets afgekapt", ex.message)
    }

    @ParameterizedTest(name = "opgehaald={0}, totaal={1}")
    @CsvSource("500, 501", "0, 1", "500,")
    fun `een totaal boven het opgehaalde, of geen totaal, is geldig`(opgehaald: Int, totaal: Long?) {
        assertEquals(totaal, Afgekapt("magazijn-a", "A", opgehaald, totaal).totaalBeschikbaar)
    }
}
