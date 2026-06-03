package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit-tests voor [CloudEventBuilder]. Borgt:
 *  - NL GOV CloudEvents v1.1 verplichte attributen aanwezig en correct
 *  - `source` URN-notatie met `urn:nld:oin:<OIN>:systeem:fbs-magazijn`
 *  - `subject` = berichtId, NIET BSN/RSIN (NL GOV: geen persoonsgegevens in context-attributen)
 *  - deterministische `id` per (berichtId, doel) — retry produceert dezelfde id
 *  - verschillende doelen → verschillende id's
 */
class CloudEventBuilderTest {

    private val oin = "00000001823288444000"
    private val config = mockk<PublicatieConfig>().apply {
        every { organisatie() } returns mockk { every { oin() } returns oin }
    }
    private val builder = CloudEventBuilder(config)

    private val bsnWaarde = "999993653"
    private val bericht = Bericht(
        berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        afzender = Oin("00000001003214345000"),
        ontvanger = Bsn(bsnWaarde),
        onderwerp = "Voorlopige aanslag 2026",
        inhoud = "Hierbij ontvangt u uw aanslag.",
        tijdstipOntvangst = Instant.parse("2026-05-12T10:00:00Z"),
        publicatiedatum = Instant.parse("2026-05-12T10:00:00Z"),
    )
    private val nu = Instant.parse("2026-05-12T10:00:05Z")

    private val aanmeld = Publicatiedoel("aanmeld")
    private val notificatie = Publicatiedoel("notificatie")

    @Test
    fun `verplichte NL GOV attributen aanwezig`() {
        val event = builder.bouw(bericht, aanmeld, nu)
        assertEquals(CloudEventBuilder.SPEC_VERSION, event.specversion)
        assertEquals(CloudEventBuilder.EVENT_TYPE, event.type)
        assertEquals("urn:nld:oin:$oin:systeem:fbs-magazijn", event.source)
        assertEquals(nu, event.time)
        assertEquals("application/json", event.datacontenttype)
        assertEquals(CloudEventBuilder.DATASCHEMA, event.dataschema)
    }

    @Test
    fun `subject is berichtId en niet BSN of ander persoonsgegeven`() {
        val event = builder.bouw(bericht, aanmeld, nu)
        assertEquals(bericht.berichtId.toString(), event.subject)
        assertFalse(event.subject.contains(bsnWaarde), "subject bevat BSN; lekt persoonsgegeven via NL GOV-context")
    }

    @Test
    fun `event-type volgt reverse domain name notation conform NL GOV`() {
        val event = builder.bouw(bericht, aanmeld, nu)
        assertTrue(event.type.startsWith("nl."), "type moet met `nl.` beginnen: ${event.type}")
        assertTrue(event.type.contains("."), "type moet reverse domain name notation gebruiken: ${event.type}")
    }

    @Test
    fun `event-id deterministisch per bericht en doel`() {
        val a1 = builder.bouw(bericht, aanmeld, nu).id
        val a2 = builder.bouw(bericht, aanmeld, nu.plusSeconds(60)).id
        assertEquals(a1, a2)
    }

    @Test
    fun `event-id verschilt per doel`() {
        val aanmeldId = builder.bouw(bericht, aanmeld, nu).id
        val notificatieId = builder.bouw(bericht, notificatie, nu).id
        assertNotEquals(aanmeldId, notificatieId)
    }

    @Test
    fun `data-payload bevat berichtinhoud en ontvanger-info`() {
        val event = builder.bouw(bericht, aanmeld, nu)
        assertEquals(bericht.berichtId, event.data.berichtId)
        assertEquals(bericht.onderwerp, event.data.onderwerp)
        assertEquals(bericht.inhoud, event.data.inhoud)
        assertEquals(bericht.afzender.waarde, event.data.afzender)
        assertEquals("BSN", event.data.ontvanger.type)
        assertEquals(bsnWaarde, event.data.ontvanger.waarde)
        assertEquals(bericht.tijdstipOntvangst, event.data.tijdstipOntvangst)
        assertEquals(bericht.publicatiedatum, event.data.publicatiedatum)
    }

    @Test
    fun `OntvangerData weigert ongeldig BSN (elfproef-bewaking in payload)`() {
        // Een rauw `(type=BSN, waarde=...)` met ongeldige elfproef mag de outbox
        // niet in lekken — voorkomt regressie na refactor.
        org.junit.jupiter.api.assertThrows<nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException> {
            OntvangerData(type = "BSN", waarde = "111111111")
        }
    }

    @Test
    fun `OntvangerData_van bouwt vanuit gevalideerd Identificatienummer`() {
        val data = OntvangerData.van(nl.rijksoverheid.moz.fbs.common.identificatie.Bsn("999993653"))
        assertEquals("BSN", data.type)
        assertEquals("999993653", data.waarde)
    }

    @Test
    fun `OntvangerData met onbekend type gooit DomainValidationException MET STATISCHE message (CRLF-injection-regressie)`() {
        // Round 12 H1: voorheen interpoleerde de message `$type` direct.
        // Een bogus type met CRLF/HTML-injection moet door de mapper als
        // statisch detail naar de client gaan, NIET de raw payload.
        // Refactor die de interpolatie herinvoert wordt hier gevangen.
        val attackPayload = "BOGUS\r\nLevel: ERROR\nInjected line"
        val ex = org.junit.jupiter.api.assertThrows<nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException> {
            OntvangerData(type = attackPayload, waarde = "x")
        }
        // Statische message (geen $type-interpolatie meer)
        assertEquals(
            "Onbekend identificatienummer-type",
            ex.message,
            "message moet statisch zijn — gevonden: ${ex.message}",
        )
        assertFalse(
            ex.message!!.contains("BOGUS"),
            "raw type-payload mag niet in client-zichtbare message — gevonden: ${ex.message}",
        )
        assertFalse(
            ex.message!!.contains("\r") || ex.message!!.contains("\n"),
            "CRLF mag niet in message (CWE-117) — gevonden: ${ex.message}",
        )
    }

    @Test
    fun `OntvangerData met onbekend type geeft type-waarde mee als cause voor support-correlatie`() {
        // Round 13 H1: type-waarde verdween eerder volledig; support kon niet
        // achterhalen welke bogus-input binnenkwam. Cause draagt de waarde nu
        // (DomainValidationExceptionMapper logt cause-message gesaneerd).
        val ex = org.junit.jupiter.api.assertThrows<nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException> {
            OntvangerData(type = "ONBEKEND_TYPE_X", waarde = "999993653")
        }
        val cause = ex.cause
        org.junit.jupiter.api.Assertions.assertNotNull(cause, "cause vereist voor support-correlatie")
        assertTrue(
            cause!!.message!!.contains("ONBEKEND_TYPE_X"),
            "cause-message moet type-waarde bevatten — gevonden: ${cause.message}",
        )
    }

    @Test
    fun `OntvangerData filtert niet-numerieke PII uit type cause-message (BIO 12_4_1, AVG art 5_1_c)`() {
        // Round 14 H1: aanvaller stuurt PII-string als type ("type=jan@voorbeeld.nl"
        // of "type=Jan Jansen Kerkstraat 1"). FoutBeschrijving.saneer redact
        // alleen ≥7-cijfer-runs + control-chars — namen, e-mail, adres glippen door.
        // Whitelist [A-Za-z0-9_] op type-veld vóór cause-meegave: alle interessante
        // PII-bytes (`@`, spatie, `.`, `,`, `:`) worden weg-gefilterd.
        val piiPayload = "jan.jansen@voorbeeld.nl Kerkstraat 1, Amsterdam"
        val ex = org.junit.jupiter.api.assertThrows<nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException> {
            OntvangerData(type = piiPayload, waarde = "999993653")
        }
        val causeMessage = ex.cause!!.message!!
        assertFalse(
            causeMessage.contains("@"),
            "e-mail at-sign mag niet door — gevonden: $causeMessage",
        )
        assertFalse(
            causeMessage.contains("."),
            "punt mag niet door (e-mail/IP-vorm) — gevonden: $causeMessage",
        )
        assertFalse(
            causeMessage.contains(" "),
            "spatie mag niet door (naam/adres) — gevonden: $causeMessage",
        )
        assertFalse(
            causeMessage.contains(","),
            "komma mag niet door (adres) — gevonden: $causeMessage",
        )
    }

    @Test
    fun `OntvangerData begrenst oversize type in cause-message (DoS-mitigatie)`() {
        // Voorkomt log-volume-DoS: aanvaller stuurt 1 MB type-waarde, cause
        // mag niet ongebreideld groeien. take(64) begrenst.
        val oversizeType = "X".repeat(10_000)
        val ex = org.junit.jupiter.api.assertThrows<nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException> {
            OntvangerData(type = oversizeType, waarde = "999993653")
        }
        val causeMessage = ex.cause!!.message!!
        assertTrue(
            causeMessage.length <= 100,
            "cause-message moet begrensd zijn — lengte: ${causeMessage.length}",
        )
    }
}
