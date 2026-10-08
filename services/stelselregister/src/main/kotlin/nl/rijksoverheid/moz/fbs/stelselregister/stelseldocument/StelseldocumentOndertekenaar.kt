package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Eén uitgegeven exemplaar van het stelseldocument: de ondertekende bytes en wat een resource
 * nodig heeft om ze uit te leveren zonder de JWS opnieuw te ontleden.
 */
data class Uitgifte(
    val jws: String,
    val versie: String,
    val kid: String,
    val uitgegevenOp: Instant,
    val verlooptOp: Instant,
    val aantalOrganisaties: Int,
) {

    /** Zwak: twee exemplaren met gelijke inhoud maar een andere `iat` zijn voor een afnemer niet uitwisselbaar. */
    val etag: String = "W/\"$versie.$kid.${uitgegevenOp.epochSecond}\""

    fun isGeldigOp(moment: Instant): Boolean = moment.isBefore(verlooptOp)
}

/**
 * Bouwt en ondertekent een stelseldocument. Kent geen HTTP en geen planning, zodat ondertekenen
 * later naar een beheerhandeling kan verhuizen zonder de publieke dienst te herschrijven.
 */
class StelseldocumentOndertekenaar(
    private val uitgeverOin: String,
    private val omgeving: String,
    private val geldigheid: Duration,
) {

    fun onderteken(document: Stelseldocument, sleutel: Ondertekensleutel, nu: Instant): Uitgifte {
        // Afgekapt op seconden: `iat` en `exp` zijn NumericDate, en de ETag moet bij de payload passen.
        val uitgegevenOp = Instant.ofEpochSecond(nu.epochSecond)
        val certificaatVerloopt = Instant.ofEpochSecond(sleutel.certificaat.notAfter.toInstant().epochSecond)

        if (!uitgegevenOp.isBefore(certificaatVerloopt)) {
            throw OngeldigeOndertekensleutelException(
                "Het ondertekencertificaat (kid ${sleutel.kid}) is verlopen op $certificaatVerloopt; er wordt niets uitgegeven",
            )
        }

        // De uitgever in het document moet de organisatie uit het certificaat zijn. Een afnemer
        // controleert hetzelfde; hier voorkomt het dat de dienst een document uitgeeft dat elke
        // afnemer terecht weigert.
        if (sleutel.uitgeverOin != uitgeverOin) {
            throw OngeldigeOndertekensleutelException(
                "Het ondertekencertificaat (kid ${sleutel.kid}) draagt in subject.serialNumber " +
                    "'${sleutel.uitgeverOin ?: "geen OIN"}', maar de uitgever van het stelseldocument is '$uitgeverOin'",
            )
        }

        // Een handtekening geldt nooit langer dan het certificaat waar hij op steunt.
        val verlooptOp = minOf(uitgegevenOp.plus(geldigheid), certificaatVerloopt)

        val header = linkedMapOf(
            "alg" to ALGORITME,
            "typ" to TYPE,
            "kid" to sleutel.kid,
            "x5c" to sleutel.x5c,
        )
        val teOndertekenen = BASE64URL.encodeToString(JSON.writeValueAsBytes(header)) + "." +
            BASE64URL.encodeToString(document.payload(uitgeverOin, omgeving, uitgegevenOp, verlooptOp))
        val handtekening = sleutel.onderteken(teOndertekenen.toByteArray(Charsets.US_ASCII))

        return Uitgifte(
            jws = teOndertekenen + "." + BASE64URL.encodeToString(handtekening),
            versie = document.versie,
            kid = sleutel.kid,
            uitgegevenOp = uitgegevenOp,
            verlooptOp = verlooptOp,
            aantalOrganisaties = document.organisaties.size,
        )
    }

    companion object {
        const val ALGORITME = "ES256"

        /** Eigen type, zodat het document niet te verwisselen is met een ander token onder dezelfde sleutel. */
        const val TYPE = "stelseldocument+jwt"

        private val JSON = ObjectMapper()
        private val BASE64URL = Base64.getUrlEncoder().withoutPadding()
    }
}
