package nl.rijksoverheid.moz.fbs.rechtstreeks

import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Instant

/**
 * De sleutel waarmee een Verifier zijn challenges tekent. [kid] is de sleutel-id waaronder de
 * public key in het stelseldocument staat; de app kiest daarop de sleutel om te controleren.
 */
class VerifierSleutel(val kid: String, internal val privateKey: ECPrivateKey) {

    init {
        require(kid.isNotBlank()) { "kid mag niet leeg zijn" }
        require(privateKey.params.curve == Jose.P256.curve) { "de sleutel van een Verifier moet op P-256 liggen" }
    }

    override fun toString(): String = "VerifierSleutel(kid=$kid)"
}

/** Een publieke sleutel van een App Manager, met het moment waarop hij verloopt (4.6). */
class UitgeverSleutel(val publicKey: ECPublicKey, val geldigTot: Instant)

/**
 * Zoekt de sleutel van een App Manager op. De bron is het stelseldocument dat de dienst zelf
 * heeft geverifieerd; een sleutel komt nooit uit het credential of zijn header.
 */
fun interface UitgeverSleutels {

    /** De sleutel van App Manager [iss] met sleutel-id [kid], of `null` als die niet vertrouwd is. */
    fun zoek(iss: String, kid: String?): UitgeverSleutel?

    companion object {

        /** Voor een Verifier die alleen self-signed presentaties aanneemt, zoals de App Manager zelf. */
        val GEEN = UitgeverSleutels { _, _ -> null }
    }
}
