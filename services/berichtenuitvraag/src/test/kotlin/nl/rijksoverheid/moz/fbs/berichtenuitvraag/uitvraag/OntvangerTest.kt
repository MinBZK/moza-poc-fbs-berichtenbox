package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Test de pure parser [splitOntvanger], de basis onder de PII-invariant: alleen
 * een geldig `Type:waarde`-format levert een non-null paar, en daarmee pas een
 * dataSubject in de LDV-audittrail (AVG art. 30). Een ongeldige waarde → `null` →
 * [registreerLdvSubject] schrijft géén subject. De end-to-end-registratie zelf
 * staat in [LdvSubjectRegistrationTest]; de fuzz-invariant in OntvangerFuzzer.
 */
class OntvangerTest {

    @Test
    fun `geldige X-Ontvanger splitst in type en waarde`() {
        val parsed = splitOntvanger("BSN:999990019")

        assertEquals("BSN" to "999990019", parsed)
    }

    @Test
    fun `RSIN-OIN-KVK worden ook geaccepteerd`() {
        assertEquals("RSIN" to "999990019", splitOntvanger("RSIN:999990019"))
        assertEquals("OIN" to "00000001234567890000", splitOntvanger("OIN:00000001234567890000"))
        assertEquals("KVK" to "12345678", splitOntvanger("KVK:12345678"))
    }

    @Test
    fun `onbekend type levert null`() {
        assertNull(splitOntvanger("FOO:999990019"))
    }

    @Test
    fun `waarde met niet-cijfers levert null`() {
        // Parser en validator delen dezelfde regex: een extra dubbele punt of letters
        // in de waarde matcht ONTVANGER_PATTERN niet en levert dus géén dataSubject.
        assertNull(splitOntvanger("BSN:123:456"))
        assertNull(splitOntvanger("BSN:12ab34"))
    }

    @Test
    fun `X-Ontvanger zonder dubbele punt levert null`() {
        assertNull(splitOntvanger("GEENCOLON"))
    }

    @Test
    fun `lege X-Ontvanger levert null`() {
        assertNull(splitOntvanger(""))
    }
}
