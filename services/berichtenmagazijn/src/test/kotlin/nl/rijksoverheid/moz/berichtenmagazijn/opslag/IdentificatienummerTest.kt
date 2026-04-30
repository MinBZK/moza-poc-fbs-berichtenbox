package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.berichtenmagazijn.opslag.IdentificatienummerType.BSN
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.IdentificatienummerType.KVK
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.IdentificatienummerType.OIN
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.IdentificatienummerType.RSIN
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentificatienummerTest {

    // --- Oin -------------------------------------------------------------------

    @Test
    fun `Oin met 20 cijfers is geldig`() {
        val oin = Oin("00000001003214345000")
        assertEquals("00000001003214345000", oin.waarde)
        assertEquals(OIN, oin.type)
    }

    @Test
    fun `Oin met 19 cijfers faalt`() {
        assertThrows(DomainValidationException::class.java) { Oin("0000000100321434500") }
    }

    @Test
    fun `Oin met 21 cijfers faalt`() {
        assertThrows(DomainValidationException::class.java) { Oin("000000010032143450000") }
    }

    @Test
    fun `Oin met letters faalt`() {
        assertThrows(DomainValidationException::class.java) { Oin("00000001003214345abc") }
    }

    // --- Kvk -------------------------------------------------------------------

    @Test
    fun `Kvk met 8 cijfers is geldig`() {
        val kvk = Kvk("12345678")
        assertEquals("12345678", kvk.waarde)
        assertEquals(KVK, kvk.type)
    }

    @Test
    fun `Kvk met 7 cijfers faalt`() {
        assertThrows(DomainValidationException::class.java) { Kvk("1234567") }
    }

    @Test
    fun `Kvk met 9 cijfers faalt`() {
        assertThrows(DomainValidationException::class.java) { Kvk("123456789") }
    }

    @Test
    fun `Kvk bestaande uit acht nullen wordt geweigerd`() {
        val ex = assertThrows(DomainValidationException::class.java) { Kvk("00000000") }
        assertTrue(
            ex.message?.contains("nullen") == true,
            "bericht moet verwijzen naar nullen-check: ${ex.message}",
        )
    }

    @Test
    fun `Oin bestaande uit twintig nullen wordt geweigerd`() {
        val ex = assertThrows(DomainValidationException::class.java) { Oin("0".repeat(20)) }
        assertTrue(
            ex.message?.contains("nullen") == true,
            "bericht moet verwijzen naar nullen-check: ${ex.message}",
        )
    }

    // --- Bsn (elfproef) --------------------------------------------------------

    @Test
    fun `Bsn 999993653 is geldig (elfproef)`() {
        val bsn = Bsn("999993653")
        assertEquals("999993653", bsn.waarde)
        assertEquals(BSN, bsn.type)
    }

    @Test
    fun `Bsn 111222333 is geldig (elfproef)`() {
        Bsn("111222333")
    }

    @Test
    fun `Bsn 999993654 faalt elfproef (een digit off)`() {
        val ex = assertThrows(DomainValidationException::class.java) { Bsn("999993654") }
        assertEquals("BSN voldoet niet aan elfproef", ex.message)
    }

    @Test
    fun `Bsn met 8 cijfers faalt op lengte, niet op elfproef`() {
        val ex = assertThrows(DomainValidationException::class.java) { Bsn("99999365") }
        assertEquals("BSN moet precies 9 cijfers zijn", ex.message)
    }

    @Test
    fun `Bsn met letters faalt`() {
        assertThrows(DomainValidationException::class.java) { Bsn("99999365a") }
    }

    @Test
    fun `BSN bestaande uit negen nullen wordt geweigerd`() {
        val ex = assertThrows(DomainValidationException::class.java) { Bsn("000000000") }
        assertTrue(
            ex.message?.contains("nullen") == true,
            "bericht moet verwijzen naar nullen-check: ${ex.message}",
        )
    }

    // --- Rsin (elfproef, 9 cijfers, gescheiden van BSN) -----------------------

    @Test
    fun `Rsin met geldige elfproef is geldig en onderscheidt zich van Bsn`() {
        val rsin = Rsin("111222333")
        assertEquals("111222333", rsin.waarde)
        assertEquals(RSIN, rsin.type)
    }

    @Test
    fun `Rsin faalt elfproef`() {
        assertThrows(DomainValidationException::class.java) { Rsin("999993654") }
    }

    // --- of(type, waarde) factory ---------------------------------------------

    @Test
    fun `of KVK geeft Kvk-instance met type KVK`() {
        val id = Identificatienummer.of(KVK, "12345678")
        assertInstanceOf(Kvk::class.java, id)
        assertEquals(KVK, id.type)
    }

    @Test
    fun `of BSN geeft Bsn, niet Rsin, ook als waarde een geldige RSIN-pattern kon zijn`() {
        // Expliciet type voorkomt silent-fallback zoals length-based parse dat wel deed.
        val id = Identificatienummer.of(BSN, "999993653")
        assertInstanceOf(Bsn::class.java, id)
        assertEquals(BSN, id.type)
    }

    @Test
    fun `of RSIN geeft Rsin, niet Bsn, bij dezelfde 9-cijferige waarde`() {
        val id = Identificatienummer.of(RSIN, "111222333")
        assertInstanceOf(Rsin::class.java, id)
        assertEquals(RSIN, id.type)
    }

    @Test
    fun `of OIN geeft Oin`() {
        val id = Identificatienummer.of(OIN, "00000001003214345000")
        assertInstanceOf(Oin::class.java, id)
        assertEquals(OIN, id.type)
    }

    @Test
    fun `of normaliseert NIET — whitespace wordt geweigerd`() {
        // Strikt: of() verbergt geen clientfouten via trim. De OpenAPI-pattern
        // dwingt cijfers-only af aan de rand; whitespace doorlaten zou inconsistent
        // zijn met de directe value-class constructors.
        val ex = assertThrows(DomainValidationException::class.java) {
            Identificatienummer.of(BSN, "  999993653  ")
        }
        assertEquals("BSN moet precies 9 cijfers zijn", ex.message)
    }

    @Test
    fun `of BSN met waarde die niet aan elfproef voldoet faalt met duidelijke boodschap`() {
        val ex = assertThrows(DomainValidationException::class.java) {
            Identificatienummer.of(BSN, "999993654")
        }
        assertEquals("BSN voldoet niet aan elfproef", ex.message)
    }
}
