package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.IdentificatienummerType
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Bouwt een CloudEvent-envelope conform NL GOV CloudEvents-profiel v1.1 (op basis
 * van CloudEvents core spec v1.0; `specversion` blijft daarom `"1.0"`).
 *
 * Verplichte attributen volgens NL GOV: `id`, `source` (URN met `nld`-namespace),
 * `specversion` ("1.0"), `type` (Reverse Domain Name Notation).
 * Optionele attributen die we zetten: `subject`, `time`, `datacontenttype`,
 * `dataschema`, `data`.
 *
 * Privacy-richtlijn (NL GOV): geen persoonsgegevens in context-attributen. Daarom
 * is `subject` de `berichtId`, niet de BSN/RSIN/KvK van de ontvanger. De data-payload
 * bevat het volledige bericht inline; berichten zijn klein genoeg om direct mee te
 * sturen.
 */
@ApplicationScoped
class CloudEventBuilder(
    private val config: PublicatieConfig,
) {

    fun bouw(bericht: Bericht, doel: Publicatiedoel, tijdstip: Instant): CloudEvent {
        val source = "urn:nld:oin:${config.organisatie().toOin().waarde}:systeem:fbs-magazijn"
        return CloudEvent(
            id = deterministischeEventId(bericht.berichtId, doel.key),
            source = source,
            specversion = SPEC_VERSION,
            type = EVENT_TYPE,
            subject = bericht.berichtId.toString(),
            time = tijdstip,
            datacontenttype = "application/json",
            dataschema = DATASCHEMA,
            data = BerichtData(
                berichtId = bericht.berichtId,
                afzender = bericht.afzender.waarde,
                ontvanger = OntvangerData.van(bericht.ontvanger),
                onderwerp = bericht.onderwerp,
                inhoud = bericht.inhoud,
                tijdstipOntvangst = bericht.tijdstipOntvangst,
                publicatiedatum = bericht.publicatiedatum,
            ),
        )
    }

    /**
     * UUIDv5 (SHA-1-gebaseerd) over de namespace + (berichtId, doel). Een retry
     * naar dezelfde downstream geeft dezelfde event-id; een nieuwe downstream
     * krijgt een andere id. (RFC 4122 §4.3 — geen randomness, dus reproduceerbaar.)
     */
    private fun deterministischeEventId(berichtId: UUID, doel: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val nsBytes = uuidToBytes(CloudEventIdNamespace.UUID_V5_NAMESPACE)
        md.update(nsBytes)
        md.update("$berichtId|$doel".toByteArray(Charsets.UTF_8))
        val hash = md.digest()
        // Zet versie- en variant-bits zoals RFC 4122 voorschrijft voor UUIDv5.
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x50).toByte() // versie 5
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte() // variant 10
        val msb = (0..7).fold(0L) { acc, i -> (acc shl 8) or (hash[i].toLong() and 0xFF) }
        val lsb = (8..15).fold(0L) { acc, i -> (acc shl 8) or (hash[i].toLong() and 0xFF) }
        return UUID(msb, lsb).toString()
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val bytes = ByteArray(16)
        for (i in 0..7) bytes[i] = (msb shr ((7 - i) * 8) and 0xFF).toByte()
        for (i in 0..7) bytes[8 + i] = (lsb shr ((7 - i) * 8) and 0xFF).toByte()
        return bytes
    }

    companion object {
        // Contract-constanten
        const val SPEC_VERSION = "1.0"
        const val EVENT_TYPE = "nl.rijksoverheid.fbs.bericht.gepubliceerd"
        const val DATASCHEMA = "https://schemas.fbs.rijksoverheid.nl/bericht-gepubliceerd/v1"
    }
}

/**
 * Serialisatie-vriendelijke representatie van een NL GOV CloudEvent.
 * `time` als [Instant] zodat Jackson naar ISO-8601 serializeert.
 */
data class CloudEvent(
    val id: String,
    val source: String,
    val specversion: String,
    val type: String,
    val subject: String,
    val time: Instant,
    val datacontenttype: String,
    val dataschema: String,
    val data: BerichtData,
)

data class BerichtData(
    val berichtId: UUID,
    val afzender: String,
    val ontvanger: OntvangerData,
    val onderwerp: String,
    val inhoud: String,
    val tijdstipOntvangst: Instant,
    val publicatiedatum: Instant,
)

/**
 * DTO van de ontvanger zoals die in de CloudEvent-payload terechtkomt. Omhult geen
 * raw `(type, waarde)` strings maar valideert bij constructie via
 * [Identificatienummer.of]; downstream consumers krijgen daarmee dezelfde
 * formaat-garanties die het domein zelf hanteert (BSN: lengte 9 + elfproef;
 * RSIN: lengte 9 + elfproef; KvK: lengte 8; OIN: lengte 20 zonder elfproef).
 * Deze invariant voorkomt dat een toekomstige tussenmapping per ongeluk een
 * ongeldig identificatienummer de outbox in lekt.
 *
 * Jackson serializeert/deserializeert via de standaard data-class-constructor
 * — de `init`-validatie loopt bij elke constructie, ook bij read uit JSON.
 */
data class OntvangerData(
    val type: String,
    val waarde: String,
) {
    init {
        // Getypeerde fabriek i.p.v. `valueOf`: die zou een raw IllegalArgumentException
        // gooien (→ 500 via UncaughtExceptionMapper); de wrap geeft een consistente 400.
        // Message is statisch: `OntvangerData` is Jackson-deserialiseerbaar uit cross-org
        // payloads, dus `type` interpoleren zou CRLF/JSON-injection in `Problem.detail`
        // toelaten (client kent de enum-namen al via de spec).
        //
        // De raw `type` gaat wél als `cause`-message mee voor support-debug, met een
        // whitelist-filter [A-Za-z0-9_] erop: enum-namen (`BSN`/`KVK`/...) blijven intact,
        // maar niet-numerieke PII (`type="jan@..."`) wordt weg-gefilterd vóór het log —
        // `FoutBeschrijving.saneer` dekt die zelf niet (alleen ≥7-cijfer + control-chars).
        // `take(64)` begrenst oversize-DoS (BIO 12.4.1, AVG art. 5.1.c).
        val typeVoorLog = type.take(64).filter { it.isLetterOrDigit() || it == '_' }
        val typed = enumValues<IdentificatienummerType>().firstOrNull { it.name == type }
            ?: throw nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException(
                "Onbekend identificatienummer-type",
                cause = IllegalArgumentException("type=$typeVoorLog"),
            )
        Identificatienummer.of(typed, waarde)
    }

    companion object {
        fun van(identificatie: Identificatienummer): OntvangerData =
            OntvangerData(type = identificatie.type.name, waarde = identificatie.waarde)
    }
}
