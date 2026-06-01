package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.mockk
import io.mockk.verify
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Test de pure parser [splitOntvanger], de basis onder de PII-invariant: alleen
 * een geldig `Type:waarde`-format levert een non-null [OntvangerHeader], en daarmee
 * pas een dataSubject in de LDV-audittrail (AVG art. 30). Een ongeldige waarde →
 * `null` → [registreerLdvSubject] schrijft géén subject en faalt luid. De end-to-end-
 * registratie zelf staat in [LdvSubjectRegistrationTest]; de fuzz-invariant in
 * OntvangerFuzzer.
 */
class OntvangerTest {

    @Test
    fun `geldige X-Ontvanger splitst in type en waarde`() {
        val parsed = splitOntvanger("BSN:999990019")

        assertEquals(OntvangerHeader("BSN", "999990019"), parsed)
    }

    @Test
    fun `RSIN-OIN-KVK worden ook geaccepteerd`() {
        assertEquals(OntvangerHeader("RSIN", "999990019"), splitOntvanger("RSIN:999990019"))
        assertEquals(OntvangerHeader("OIN", "00000001234567890000"), splitOntvanger("OIN:00000001234567890000"))
        assertEquals(OntvangerHeader("KVK", "12345678"), splitOntvanger("KVK:12345678"))
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

    @Test
    fun `registreerLdvSubject zet type en waarde bij een geldige X-Ontvanger`() {
        val ctx = mockk<LogboekContext>(relaxed = true)

        registreerLdvSubject(ctx, "BSN:999990019")

        verify { ctx.dataSubjectType = "BSN" }
        verify { ctx.dataSubjectId = "999990019" }
    }

    @Test
    fun `registreerLdvSubject faalt luid en zet geen subject bij een ongeldige waarde`() {
        // Invariant-breuk-tak (regex-divergentie / validator-bypass): mag nooit een
        // dataSubject-loos LDV-record onder een 2xx laten ontstaan. We borgen dat de
        // functie gooit én dat er géén (gedeeltelijk) subject is gezet — de AVG art. 30-
        // veiligheidseigenschap, los van de @Pattern-validatie op het endpoint.
        val ctx = mockk<LogboekContext>(relaxed = true)

        assertThrows(IllegalStateException::class.java) {
            registreerLdvSubject(ctx, "GEENCOLON")
        }

        verify(exactly = 0) { ctx.dataSubjectType = any() }
        verify(exactly = 0) { ctx.dataSubjectId = any() }
    }
}
