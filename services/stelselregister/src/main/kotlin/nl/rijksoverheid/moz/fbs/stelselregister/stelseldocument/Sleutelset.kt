package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * De publieke sleutels van de dienst als JWK Set (RFC 7517): de actieve ondertekensleutel voorop,
 * daarna de andere ondertekencertificaten uit de keystore, zodat een afnemer tijdens een
 * sleutelwissel zowel de oude als de nieuwe sleutel kan inzien.
 */
class Sleutelset(sleutel: Ondertekensleutel) {

    private val sleutels: List<Map<String, Any>> = (listOf(sleutel.certificaat) + sleutel.overige).map(Ondertekensleutel::jwk)

    val json: String = JSON.writeValueAsString(mapOf("keys" to sleutels))

    /** Uit alle kid's: de set verandert precies wanneer er een sleutel bijkomt, wegvalt of wisselt. */
    val etag: String = "W/\"${sleutels.joinToString(".") { it.getValue("kid").toString() }}\""

    private companion object {
        private val JSON = ObjectMapper()
    }
}
