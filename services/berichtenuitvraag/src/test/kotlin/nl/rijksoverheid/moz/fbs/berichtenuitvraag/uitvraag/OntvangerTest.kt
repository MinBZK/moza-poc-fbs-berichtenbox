package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Vergrendelt de PII-invariant achter [registreerLdvSubject]: alleen een geldig
 * `Type:waarde`-format levert een dataSubject voor de LDV-audittrail (AVG art.
 * 30). De resource-paden raken deze logica altijd ná Bean Validation, dus de
 * malformed-tak komt daar nooit aan bod — deze pure unit-test dekt die guard.
 */
class OntvangerTest {

    @Test
    fun `geldige X-Ontvanger splitst in type en waarde`() {
        val parsed = splitOntvanger("BSN:123456782")

        assertEquals("BSN" to "123456782", parsed)
    }

    @Test
    fun `RSIN-OIN-KVK worden ook geaccepteerd`() {
        assertEquals("RSIN" to "123456782", splitOntvanger("RSIN:123456782"))
        assertEquals("OIN" to "00000001234567890000", splitOntvanger("OIN:00000001234567890000"))
        assertEquals("KVK" to "12345678", splitOntvanger("KVK:12345678"))
    }

    @Test
    fun `onbekend type levert null`() {
        assertNull(splitOntvanger("FOO:123456782"))
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
