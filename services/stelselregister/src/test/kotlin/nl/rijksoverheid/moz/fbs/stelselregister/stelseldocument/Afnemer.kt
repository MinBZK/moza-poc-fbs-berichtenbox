package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jws.JsonWebSignature
import java.io.ByteArrayInputStream
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Verifieert een stelseldocument zoals de API-beschrijving een afnemer voorschrijft, met een
 * JOSE-implementatie die niets deelt met de code die ondertekent. Een test die hier slaagt zegt
 * dus iets over wat een app kan, niet alleen dat de dienst met zichzelf overeenstemt.
 */
class Afnemer(private val root: X509Certificate, private val verwachteUitgever: String) {

    class Geweigerd(reden: String, cause: Throwable? = null) : Exception(reden, cause)

    fun accepteer(jws: String, nu: Instant): JsonNode {
        val delen = jws.split('.')

        if (delen.size != 3) throw Geweigerd("geen compacte JWS")

        val header = JSON.readTree(Base64.getUrlDecoder().decode(delen[0]))

        if (header.path("alg").asText() != "ES256") throw Geweigerd("alg is niet ES256")
        if (header.path("typ").asText() != "stelseldocument+jwt") throw Geweigerd("typ klopt niet")

        val keten = header.path("x5c").map { certificaat(it.asText()) }

        if (keten.isEmpty()) throw Geweigerd("geen x5c")

        valideerKeten(keten, nu)
        controleerHandtekening(jws, keten.first())

        val payload = JSON.readTree(Base64.getUrlDecoder().decode(delen[1]))

        if (payload.path("exp").asLong() <= nu.epochSecond) throw Geweigerd("document is verlopen")
        if (payload.path("iss").asText() != verwachteUitgever) throw Geweigerd("onverwachte uitgever")

        return payload
    }

    private fun valideerKeten(keten: List<X509Certificate>, nu: Instant) {
        val parameters = PKIXParameters(setOf(TrustAnchor(root, null))).apply {
            isRevocationEnabled = false
            date = Date.from(nu)
        }

        try {
            CertPathValidator.getInstance("PKIX").validate(X509.generateCertPath(keten), parameters)
        } catch (e: java.security.GeneralSecurityException) {
            throw Geweigerd("keten leidt niet naar de vastgelegde root", e)
        }
    }

    private fun controleerHandtekening(jws: String, certificaat: X509Certificate) {
        val verificatie = JsonWebSignature().apply {
            setAlgorithmConstraints(AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, "ES256"))
            compactSerialization = jws
            key = certificaat.publicKey
        }

        if (!verificatie.verifySignature()) throw Geweigerd("handtekening klopt niet")
    }

    companion object {
        val JSON = ObjectMapper()
        private val X509 = CertificateFactory.getInstance("X.509")

        fun certificaat(base64: String): X509Certificate =
            X509.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64))) as X509Certificate

        fun header(jws: String): JsonNode = JSON.readTree(Base64.getUrlDecoder().decode(jws.substringBefore('.')))

        fun payload(jws: String): JsonNode = JSON.readTree(Base64.getUrlDecoder().decode(jws.split('.')[1]))
    }
}
