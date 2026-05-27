package nl.rijksoverheid.moz.fbs.common.identificatie

import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType.BSN
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType.KVK
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType.OIN
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType.RSIN
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    // --- toCanonicalString -----------------------------------------------------

    @Test
    fun `toCanonicalString geeft TYPE WAARDE formaat`() {
        assertEquals("BSN:999993653", Bsn("999993653").toCanonicalString())
        assertEquals("RSIN:111222333", Rsin("111222333").toCanonicalString())
        assertEquals("KVK:12345678", Kvk("12345678").toCanonicalString())
        assertEquals("OIN:00000001003214345000", Oin("00000001003214345000").toCanonicalString())
    }

    @Test
    fun `toCanonicalString roundtrip met fromHeader`() {
        val origineel: Identificatienummer = Bsn("999993653")
        val header = origineel.toCanonicalString()
        val geparsed = Identificatienummer.fromHeader(header)
        assertEquals(origineel, geparsed)
    }

    // --- toString (hash-suffix voor BSN/RSIN, volledig voor KVK/OIN) -----------

    @Test
    fun `Bsn toString geeft hash-suffix en lekt geen cijfers van de waarde`() {
        // Defense-in-depth: een toekomstige `log.warnf("Voor %s", bsn)` mag NOOIT
        // het BSN lekken. SHA-256 4-hex-suffix: one-way, niet herleidbaar.
        val bsn = Bsn("999993653")
        val rendered = bsn.toString()

        // Format: BSN:#<4-hex-chars>
        assertTrue(rendered.matches(Regex("^BSN:#[0-9a-f]{4}$")), "Onverwacht format: $rendered")
        // Geen enkel cijfer van de oorspronkelijke waarde mag in de output verschijnen
        // als reeks van >= 3 chars (4-hex kan toevallig cijfers bevatten, maar nooit de
        // volledige BSN-reeks). Streng: check op de volledige waarde + sub-reeksen.
        assertTrue("999993653" !in rendered, "Volledige BSN-waarde mag niet in toString voorkomen")
        assertTrue("99999" !in rendered, "Substring van BSN mag niet in toString voorkomen")
    }

    @Test
    fun `Bsn toString is deterministisch (zelfde waarde geeft zelfde hash)`() {
        // Borgt log-correlatie: twee separate Bsn-instances met dezelfde waarde
        // moeten dezelfde rendering geven zodat ops via grep events kan koppelen.
        val a = Bsn("999993653").toString()
        val b = Bsn("999993653").toString()
        assertEquals(a, b)
    }

    @Test
    fun `Bsn toString geeft verschillende hash voor verschillende waardes`() {
        // Verschillende BSNs MOETEN verschillende suffixes geven om correlatie zinvol
        // te maken. Met 16-bit hash zal er ~1 op 65536 collision zijn — voor 2 hand-
        // gekozen elfproef-geldige BSNs is dat in praktijk geen probleem.
        val a = Bsn("999993653").toString()
        val b = Bsn("111222333").toString()
        assertNotEquals(a, b)
    }

    @Test
    fun `Rsin toString geeft hash-suffix`() {
        // Conservatief gemaskeerd: éénmanszaak-RSIN is herleidbaar tot BSN-houder.
        val rsin = Rsin("111222333")
        val rendered = rsin.toString()

        assertTrue(rendered.matches(Regex("^RSIN:#[0-9a-f]{4}$")), "Onverwacht format: $rendered")
        assertTrue("111222333" !in rendered, "Volledige RSIN-waarde mag niet in toString voorkomen")
    }

    @Test
    fun `Kvk toString toont de volledige waarde (publiek opvraagbaar)`() {
        assertEquals("KVK:12345678", Kvk("12345678").toString())
    }

    @Test
    fun `Oin toString toont de volledige waarde (publieke organisatie-identificator)`() {
        assertEquals("OIN:00000001003214345000", Oin("00000001003214345000").toString())
    }

    @Test
    fun `String-interpolatie met BSN gebruikt hash-suffix toString`() {
        // Borgt het defense-in-depth: regressie-test als iemand de override per
        // ongeluk verwijdert. Kotlin string-templates roepen `.toString()` aan.
        val bsn = Bsn("999993653")
        val gelogd = "ontvanger=$bsn"
        assertTrue(gelogd.matches(Regex("^ontvanger=BSN:#[0-9a-f]{4}$")), "Onverwacht: $gelogd")
        assertTrue("999993653" !in gelogd)
    }

    // --- fromHeader fout-paden -------------------------------------------------

    @Test
    fun `fromHeader zonder colon werpt DomainValidationException`() {
        // Regressie-vangnet: bij ontbrekende ':' moet de helper een gestructureerde
        // domeinfout gooien (niet IndexOutOfBounds of generieke IllegalArgument).
        val ex = assertThrows(DomainValidationException::class.java) {
            Identificatienummer.fromHeader("999993653")
        }
        assertTrue(ex.message!!.contains("<TYPE>:<WAARDE>"), "Bericht moet format-hint bevatten: ${ex.message}")
    }

    @Test
    fun `fromHeader met onbekend prefix werpt DomainValidationException`() {
        val ex = assertThrows(DomainValidationException::class.java) {
            Identificatienummer.fromHeader("FOO:123456789")
        }
        assertTrue(ex.message!!.contains("FOO"), "Bericht moet prefix vermelden: ${ex.message}")
    }

    @Test
    fun `fromHeader met lowercase prefix werpt DomainValidationException`() {
        // IdentificatienummerType.valueOf is hoofdletter-gevoelig — lowercase mag niet
        // stilzwijgend slagen (anders bypassen attackers de canonical-form-invariant).
        assertThrows(DomainValidationException::class.java) {
            Identificatienummer.fromHeader("bsn:999993653")
        }
    }

    @Test
    fun `fromHeader met geldig BSN geeft Bsn-instance`() {
        val resultaat = Identificatienummer.fromHeader("BSN:999993653")
        assertInstanceOf(Bsn::class.java, resultaat)
        assertEquals("999993653", resultaat.waarde)
    }

    @Test
    fun `fromHeader met BSN-waarde die niet aan elfproef voldoet werpt DomainValidationException`() {
        // Type-prefix is geldig, waarde-validatie (elfproef) faalt — moet doorrijden
        // naar dezelfde foutklasse zodat caller één catch-pad heeft.
        assertThrows(DomainValidationException::class.java) {
            Identificatienummer.fromHeader("BSN:999993654")
        }
    }

    @Test
    fun `fromHeader doet geen impliciete trim (whitespace = clientfout)`() {
        // Productie-comment in Identificatienummer expliciet: "Geen impliciete trim".
        // Pinned-test: spaties in waarde-deel laten de elfproef falen i.p.v. stil te normaliseren.
        assertThrows(DomainValidationException::class.java) {
            Identificatienummer.fromHeader("BSN: 999993653")
        }
    }
}
