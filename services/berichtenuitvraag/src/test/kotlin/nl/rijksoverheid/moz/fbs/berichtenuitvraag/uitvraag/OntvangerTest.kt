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
    fun `X-Ontvanger met meerdere dubbele punten splitst op de eerste`() {
        val parsed = splitOntvanger("BSN:123:456")

        assertEquals("BSN" to "123:456", parsed)
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
