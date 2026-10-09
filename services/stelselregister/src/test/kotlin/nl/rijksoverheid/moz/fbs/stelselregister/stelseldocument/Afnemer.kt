package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jws.JsonWebSignature
import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Verifieert een stelseldocument zoals de API-beschrijving een afnemer voorschrijft, met een
 * JOSE-implementatie die niets deelt met de code die ondertekent. Een test die hier slaagt zegt
 * dus iets over wat een app kan, niet alleen dat de dienst met zichzelf overeenstemt.
 *
 * De zes stappen hieronder zijn die uit de spec, in dezelfde volgorde; `AfnemerTest` houdt het
 * aantal tegen de spec aan. Eén instantie is één app: ze onthoudt het laatst geaccepteerde
 * exemplaar, zoals stap 6 vraagt.
 */
class Afnemer(
    private val root: X509Certificate,
    private val verwachteUitgever: String,
    private val verwachteOmgeving: String = "test",
) {

    class Geweigerd(reden: String, cause: Throwable? = null) : Exception(reden, cause)

    private var laatsteIat: Long = Long.MIN_VALUE

    fun accepteer(jws: String, nu: Instant): JsonNode {
        val delen = jws.split('.')

        if (delen.size != 3) throw Geweigerd("geen compacte JWS")

        val header = JSON.readTree(Base64.getUrlDecoder().decode(delen[0]))

        // Stap 1
        if (header.fieldNames().asSequence().toSet() != HEADERVELDEN) throw Geweigerd("onverwachte headervelden")
        if (header.path("alg").asText() != "ES256") throw Geweigerd("alg is niet ES256")
        if (header.path("typ").asText() != "stelseldocument+jwt") throw Geweigerd("typ klopt niet")

        // Stap 2
        val keten = header.path("x5c").map { certificaat(it.asText()) }

        if (keten.isEmpty()) throw Geweigerd("geen x5c")

        valideerKeten(keten, nu)

        // Stap 3
        controleerHandtekening(jws, keten.first())

        val payload = JSON.readTree(Base64.getUrlDecoder().decode(delen[1]))

        // Stap 4. Zonder deze band volstaat élk certificaat onder de root om zich als de uitgever voor te doen.
        if (Ondertekensleutel.oinUit(keten.first()) != verwachteUitgever) {
            throw Geweigerd("het ondertekencertificaat is niet van de verwachte uitgever")
        }

        if (payload.path("iss").asText() != verwachteUitgever) throw Geweigerd("onverwachte uitgever")

        // Stap 5
        if (payload.path("environment").asText() != verwachteOmgeving) throw Geweigerd("onverwachte omgeving")

        // Stap 6
        val iat = payload.path("iat").asLong()
        val exp = payload.path("exp").asLong()

        if (iat > nu.epochSecond + KLOKVERSCHIL.seconds) throw Geweigerd("uitgegeven in de toekomst")
        if (exp - iat > GELDIGHEID.seconds) throw Geweigerd("geldigheid langer dan het profiel toestaat")
        if (exp <= nu.epochSecond) throw Geweigerd("document is verlopen")
        if (iat < laatsteIat) throw Geweigerd("ouder dan het laatst geaccepteerde exemplaar")

        laatsteIat = iat

        return payload
    }

    private fun valideerKeten(keten: List<X509Certificate>, nu: Instant) {
        val parameters = PKIXParameters(setOf(TrustAnchor(root, null))).apply {
            isRevocationEnabled = false
            date = Date.from(nu)
        }

        try {
            CertPathValidator.getInstance("PKIX").validate(X509.generateCertPath(keten), parameters)
        } catch (e: GeneralSecurityException) {
            throw Geweigerd("keten leidt niet naar de vastgelegde root", e)
        }

        val eerste = keten.first()

        if (eerste.basicConstraints >= 0 || eerste.keyUsage?.get(0) != true) {
            throw Geweigerd("het eerste certificaat is geen ondertekencertificaat")
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
        val HEADERVELDEN = setOf("alg", "typ", "kid", "x5c")
        val KLOKVERSCHIL: Duration = Duration.ofSeconds(60)
        val GELDIGHEID: Duration = Duration.ofHours(24)

        private val X509 = CertificateFactory.getInstance("X.509")

        fun certificaat(base64: String): X509Certificate =
            X509.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64))) as X509Certificate

        fun header(jws: String): JsonNode = JSON.readTree(Base64.getUrlDecoder().decode(jws.substringBefore('.')))

        fun payload(jws: String): JsonNode = JSON.readTree(Base64.getUrlDecoder().decode(jws.split('.')[1]))
    }
}
