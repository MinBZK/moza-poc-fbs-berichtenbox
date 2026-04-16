package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.DomainValidationException
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
    }

    @Test
    fun `Kvk met 7 cijfers faalt`() {
        assertThrows(DomainValidationException::class.java) { Kvk("1234567") }
    }

    @Test
    fun `Kvk met 9 cijfers faalt`() {
        assertThrows(DomainValidationException::class.java) { Kvk("123456789") }
    }

    // --- Bsn (elfproef) --------------------------------------------------------

    @Test
    fun `Bsn 999993653 is geldig (elfproef)`() {
        val bsn = Bsn("999993653")
        assertEquals("999993653", bsn.waarde)
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

    // --- parse factory ---------------------------------------------------------

    @Test
    fun `parse 8 cijfers geeft Kvk`() {
        assertInstanceOf(Kvk::class.java, Identificatienummer.parse("12345678"))
    }

    @Test
    fun `parse 9 cijfers (geldige BSN) geeft Bsn`() {
        assertInstanceOf(Bsn::class.java, Identificatienummer.parse("999993653"))
    }

    @Test
    fun `parse 20 cijfers geeft Oin`() {
        assertInstanceOf(Oin::class.java, Identificatienummer.parse("00000001003214345000"))
    }

    @Test
    fun `parse 10 cijfers faalt met duidelijke boodschap`() {
        val ex = assertThrows(DomainValidationException::class.java) {
            Identificatienummer.parse("1234567890")
        }
        val message = ex.message ?: ""
        assertTrue(message.contains("8 (KVK)"), "bericht moet KVK-lengte noemen: $message")
        assertTrue(message.contains("9 (BSN)"), "bericht moet BSN-lengte noemen: $message")
        assertTrue(message.contains("20 (OIN)"), "bericht moet OIN-lengte noemen: $message")
    }

    @Test
    fun `parse trimt whitespace`() {
        val id = Identificatienummer.parse("  999993653  ")
        assertInstanceOf(Bsn::class.java, id)
        assertEquals("999993653", id.waarde)
    }

    @Test
    fun `BSN bestaande uit negen nullen wordt geweigerd`() {
        val ex = assertThrows(DomainValidationException::class.java) { Bsn("000000000") }
        assertTrue(
            ex.message?.contains("nullen") == true,
            "bericht moet verwijzen naar nullen-check: ${ex.message}",
        )
    }
}
