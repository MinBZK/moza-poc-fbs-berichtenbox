package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IdentificatienummerFromHeaderTest {

    @Test
    fun `fromHeader parseert BSN-header naar Bsn`() {
        val id = Identificatienummer.fromHeader("BSN:999993653")
        assertEquals(IdentificatienummerType.BSN, id.type)
        assertEquals("999993653", id.waarde)
    }

    @Test
    fun `fromHeader parseert KVK, RSIN en OIN`() {
        assertEquals(IdentificatienummerType.KVK, Identificatienummer.fromHeader("KVK:12345678").type)
        assertEquals(IdentificatienummerType.RSIN, Identificatienummer.fromHeader("RSIN:002564440").type)
        assertEquals(IdentificatienummerType.OIN, Identificatienummer.fromHeader("OIN:00000001003214345000").type)
    }

    @Test
    fun `fromHeader gooit DomainValidationException zonder dubbelepunt`() {
        assertThrows<DomainValidationException> { Identificatienummer.fromHeader("999993653") }
    }

    @Test
    fun `fromHeader gooit DomainValidationException bij onbekend type`() {
        assertThrows<DomainValidationException> { Identificatienummer.fromHeader("FOO:123456789") }
    }

    @Test
    fun `fromHeader gooit DomainValidationException bij ongeldige BSN (elfproef)`() {
        assertThrows<DomainValidationException> { Identificatienummer.fromHeader("BSN:111111111") }
    }
}
