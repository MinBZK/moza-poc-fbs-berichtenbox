package nl.rijksoverheid.moz.fbs.rechtstreeks

import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Instant

/**
 * De sleutel waarmee een Verifier zijn challenges tekent. [kid] is de sleutel-id waaronder de
 * public key in het stelseldocument staat; de app kiest daarop de sleutel om te controleren, en
 * weigert hem na [geldigTot] (4.6.1). Daarom tekent de Verifier na dat moment niet meer.
 */
class VerifierSleutel(val kid: String, internal val privateKey: ECPrivateKey, val geldigTot: Instant) {

    init {
        require(kid.isNotBlank()) { "kid mag niet leeg zijn" }
        require(privateKey.params.curve == Jose.P256.curve) { "de sleutel van een Verifier moet op P-256 liggen" }
    }

    override fun toString(): String = "VerifierSleutel(kid=$kid, geldigTot=$geldigTot)"
}

/** Een publieke sleutel van een App Manager, met het moment waarop hij verloopt (4.6). */
class AppManagerSleutel(val publicKey: ECPublicKey, val geldigTot: Instant) {

    companion object {

        /**
         * Uit de base64url-coördinaten van een P-256-JWK, met dezelfde controles als een sleutel uit
         * een credential: precies 32 bytes per coördinaat en een punt op de curve. Voor een dienst
         * die de sleutel uit zijn configuratie of uit het stelseldocument leest.
         */
        fun uitCoordinaten(x: String, y: String, geldigTot: Instant): AppManagerSleutel {
            val jwk = mapOf("kty" to "EC", "crv" to "P-256", "x" to x, "y" to y)

            val sleutel = try {
                Jose.ecSleutel(jwk, Afwijzing.APPSLEUTEL_ONGELDIG)
            } catch (fout: AfgewezenException) {
                throw IllegalArgumentException("geen geldige P-256-sleutel", fout)
            }

            return AppManagerSleutel(sleutel, geldigTot)
        }
    }
}

/**
 * Zoekt de sleutel van een App Manager op. De bron is het stelseldocument dat de dienst zelf
 * heeft geverifieerd; een sleutel komt nooit uit het credential of zijn header.
 */
fun interface AppManagerSleutels {

    /** De sleutel van App Manager [iss] met sleutel-id [kid], of `null` als die niet vertrouwd is. */
    fun zoek(iss: String, kid: String?): AppManagerSleutel?

    companion object {

        /** Voor een Verifier die alleen self-signed presentaties aanneemt, zoals de App Manager zelf. */
        val GEEN = AppManagerSleutels { _, _ -> null }
    }
}
