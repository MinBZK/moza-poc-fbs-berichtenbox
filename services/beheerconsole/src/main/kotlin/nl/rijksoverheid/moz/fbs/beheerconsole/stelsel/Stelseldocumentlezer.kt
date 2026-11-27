package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jws.JsonWebSignature
import org.jose4j.lang.JoseException
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
import java.util.concurrent.atomic.AtomicLong
import javax.naming.ldap.LdapName
import javax.security.auth.x500.X500Principal

/**
 * Leest het stelseldocument en verifieert het zoals de API-beschrijving van het stelselregister een
 * afnemer voorschrijft: zes stappen, in die volgorde, en de eerste die faalt is de reden.
 *
 * Anders dan een app weigert de console een ongeldig document niet: hij toont wat er gepubliceerd
 * staat, met de uitkomst erbij. Een beheerder wil juist dan zien wat er staat. Een document dat
 * niet eens te ontleden is, levert alleen de reden op.
 *
 * `@Startup`: een onleesbare root laat zo de start falen, en niet pas elk verzoek met een 500.
 */
@Startup
@ApplicationScoped
class Stelseldocumentlezer(config: StelseldocumentConfig) {

    private val root: X509Certificate? = config.root().map { certificaatUitPem(it) }.orElse(null)

    private val uitgever = config.uitgever()

    private val omgeving = config.omgeving()

    /** Stap 6: een exemplaar ouder dan het laatst geaccepteerde is een teruggedraaid document. */
    private val laatsteIat = AtomicLong(Long.MIN_VALUE)

    sealed interface Uitkomst {
        data object Geldig : Uitkomst

        data class Ongeldig(val reden: String) : Uitkomst

        data class NietGetoetst(val reden: String) : Uitkomst
    }

    data class Organisatie(val oin: String, val naam: String, val magazijnUrl: String)

    data class Inhoud(
        val uitgever: String,
        val omgeving: String,
        val versie: String,
        val uitgegevenOp: Instant,
        val geldigTot: Instant,
        val organisaties: List<Organisatie>,
    )

    data class Ondertekening(val kid: String, val onderwerp: String, val certificaatGeldigTot: Instant)

    data class Gelezen(val uitkomst: Uitkomst, val inhoud: Inhoud?, val ondertekening: Ondertekening?)

    private class Afgekeurd(reden: String, oorzaak: Throwable? = null) : Exception(reden, oorzaak)

    fun lees(ruw: String, nu: Instant): Gelezen {
        // Getrimd, ook voor de handtekening: witruimte om het document verandert de signing input.
        val jws = ruw.trim()
        val delen = jws.split('.')

        if (delen.size != DELEN_IN_JWS) return Gelezen(Uitkomst.Ongeldig("geen JWS in compacte vorm"), null, null)

        val header = json(delen[0]) ?: return Gelezen(Uitkomst.Ongeldig("de header is geen JSON"), null, null)
        val payload = json(delen[1]) ?: return Gelezen(Uitkomst.Ongeldig("de payload is geen JSON"), null, null)
        val keten = runCatching { header.path("x5c").map { certificaatUitDer(it.asText()) } }.getOrDefault(emptyList())
        val inhoud = runCatching { inhoud(payload) }.getOrNull()

        val ondertekening = keten.firstOrNull()?.let { eerste ->
            Ondertekening(
                kid = header.path("kid").asText(),
                onderwerp = eerste.subjectX500Principal.getName(X500Principal.RFC2253, mapOf(OID_SERIALNUMBER to SERIALNUMBER)),
                certificaatGeldigTot = keten.minOf { it.notAfter }.toInstant(),
            )
        }

        val uitkomst = try {
            verifieer(jws, header, payload, keten, nu)
        } catch (e: Afgekeurd) {
            Uitkomst.Ongeldig(e.message ?: "afgekeurd")
        }

        return Gelezen(uitkomst, inhoud, ondertekening)
    }

    private fun verifieer(
        jws: String,
        header: JsonNode,
        payload: JsonNode,
        keten: List<X509Certificate>,
        nu: Instant,
    ): Uitkomst {
        // Stap 1
        if (header.fieldNames().asSequence().toSet() != HEADERVELDEN) throw Afgekeurd("de header heeft andere velden dan alg, typ, kid en x5c")

        if (header.path("alg").asText() != "ES256") throw Afgekeurd("alg is niet ES256")

        if (header.path("typ").asText() != "stelseldocument+jwt") throw Afgekeurd("typ is niet stelseldocument+jwt")

        if (keten.isEmpty()) throw Afgekeurd("x5c ontbreekt of is niet te lezen")

        // Zonder root bewijzen stap 2 en 3 niets: het document levert zijn eigen sleutel aan.
        val vertrouwd = root ?: return Uitkomst.NietGetoetst("er is geen root van de stelselbeheerder vastgelegd")

        // Stap 2
        valideerKeten(keten, vertrouwd, nu)

        // Stap 3
        controleerHandtekening(jws, keten.first())

        // Stap 4
        if (oinUit(keten.first()) != uitgever) throw Afgekeurd("het ondertekencertificaat is niet van de verwachte stelselbeheerder")

        if (payload.path("iss").asText() != uitgever) throw Afgekeurd("iss is niet de verwachte stelselbeheerder")

        // Stap 5
        if (payload.path("environment").asText() != omgeving) throw Afgekeurd("het document is voor een andere omgeving")

        // Stap 6
        controleerTijd(tijdstip(payload, "iat"), tijdstip(payload, "exp"), nu)

        return Uitkomst.Geldig
    }

    private fun valideerKeten(keten: List<X509Certificate>, vertrouwd: X509Certificate, nu: Instant) {
        val parameters = PKIXParameters(setOf(TrustAnchor(vertrouwd, null))).apply {
            isRevocationEnabled = false
            date = Date.from(nu)
        }

        try {
            CertPathValidator.getInstance("PKIX").validate(X509.generateCertPath(keten), parameters)
        } catch (e: GeneralSecurityException) {
            throw Afgekeurd("de certificaatketen leidt niet naar de vastgelegde root", e)
        }

        val eerste = keten.first()

        if (eerste.basicConstraints >= 0 || eerste.keyUsage?.getOrNull(0) != true) {
            throw Afgekeurd("het eerste certificaat is geen ondertekencertificaat")
        }
    }

    private fun controleerHandtekening(jws: String, certificaat: X509Certificate) {
        val geldig = try {
            JsonWebSignature().apply {
                setAlgorithmConstraints(AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, "ES256"))
                compactSerialization = jws
                key = certificaat.publicKey
            }.verifySignature()
        } catch (e: JoseException) {
            throw Afgekeurd("de handtekening is niet te controleren", e)
        }

        if (!geldig) throw Afgekeurd("de handtekening klopt niet")
    }

    /** Een geheel aantal seconden na 1970; tekst, een breuk of een negatief getal telt niet. */
    private fun tijdstip(payload: JsonNode, veld: String): Long {
        val waarde = payload.path(veld)

        if (!waarde.isIntegralNumber || !waarde.canConvertToLong() || waarde.asLong() <= 0) throw Afgekeurd("$veld is geen tijdstip")

        return waarde.asLong()
    }

    private fun controleerTijd(iat: Long, exp: Long, nu: Instant) {
        if (iat > nu.epochSecond + KLOKVERSCHIL.seconds) throw Afgekeurd("uitgegeven in de toekomst")

        // Als vergelijking en niet als verschil: wie de sleutel heeft kiest exp zelf, en exp - iat kan overlopen.
        if (exp <= iat || exp > iat + GELDIGHEID.seconds) throw Afgekeurd("niet tussen nul en 24 uur geldig")

        if (exp <= nu.epochSecond) throw Afgekeurd("het document is verlopen")

        val vorige = laatsteIat.getAndAccumulate(iat) { oud, nieuw -> maxOf(oud, nieuw) }

        if (iat < vorige) throw Afgekeurd("ouder dan het laatst geaccepteerde exemplaar")
    }

    private fun inhoud(payload: JsonNode) = Inhoud(
        uitgever = payload.path("iss").asText(),
        omgeving = payload.path("environment").asText(),
        versie = payload.path("version").asText(),
        uitgegevenOp = weergaveTijdstip(payload.path("iat")),
        geldigTot = weergaveTijdstip(payload.path("exp")),
        organisaties = payload.path("organizations").map {
            Organisatie(it.path("oin").asText(), it.path("name").asText(), it.path("magazijn_url").asText())
        },
    )

    /**
     * Alleen een tijdstip dat als RFC 3339-datum te schrijven is (jaar 1 tot en met 9999). Een
     * uitgever die milliseconden stuurt, levert anders een jaartal van vijf cijfers op, en dat valt
     * buiten het schema en buiten wat een browser kan tonen. Zo'n document heeft dan geen inhoud.
     */
    private fun weergaveTijdstip(waarde: JsonNode): Instant {
        require(waarde.isIntegralNumber && waarde.asLong() in 1..LAATSTE_WEERGEEFBARE_SECONDE) { "geen weergeefbaar tijdstip" }

        return Instant.ofEpochSecond(waarde.asLong())
    }

    private fun json(deel: String): JsonNode? =
        runCatching { JSON.readTree(Base64.getUrlDecoder().decode(deel)) }.getOrNull()?.takeIf { it.isObject }

    companion object {
        private const val DELEN_IN_JWS = 3

        /** 9999-12-31T23:59:59Z. */
        private const val LAATSTE_WEERGEEFBARE_SECONDE = 253_402_300_799L

        private val HEADERVELDEN = setOf("alg", "typ", "kid", "x5c")

        private val KLOKVERSCHIL: Duration = Duration.ofSeconds(60)

        private val GELDIGHEID: Duration = Duration.ofHours(24)

        private val JSON = ObjectMapper()

        private val X509 = CertificateFactory.getInstance("X.509")

        private const val OID_SERIALNUMBER = "2.5.4.5"

        private const val SERIALNUMBER = "SERIALNUMBER"

        private fun certificaatUitDer(base64: String): X509Certificate =
            X509.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64))) as X509Certificate

        fun certificaatUitPem(pem: String): X509Certificate =
            X509.generateCertificate(ByteArrayInputStream(pem.trim().toByteArray())) as X509Certificate

        /**
         * Precies één `serialNumber` in het subject, als enkelvoudige RDN; anders geen OIN. Bij
         * twee waarden kiest de ene bibliotheek de ene en de andere de andere. Dezelfde regel als
         * in het stelselregister.
         */
        fun oinUit(certificaat: X509Certificate): String? {
            val subject = certificaat.subjectX500Principal.getName(X500Principal.RFC2253, mapOf(OID_SERIALNUMBER to SERIALNUMBER))

            val dragers = LdapName(subject).rdns.filter { rdn ->
                rdn.toAttributes().iDs.asSequence().any { it.equals(SERIALNUMBER, ignoreCase = true) }
            }

            return dragers.singleOrNull()?.takeIf { it.size() == 1 }?.value?.toString()
        }
    }
}
