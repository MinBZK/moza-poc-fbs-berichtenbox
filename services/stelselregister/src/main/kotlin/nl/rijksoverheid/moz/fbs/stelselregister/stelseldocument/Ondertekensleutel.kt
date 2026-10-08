package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.naming.ldap.LdapName
import javax.security.auth.x500.X500Principal

/** De keystore levert geen sleutel op waarmee een stelseldocument ondertekend mag worden. */
class OngeldigeOndertekensleutelException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Waar de sleutel vandaan komt; staat in de log bij elke uitgifte. */
enum class Sleutelherkomst { KEYSTORE, WEGWERP }

/**
 * De sleutel waarmee het stelseldocument wordt ondertekend, met de certificaatketen die een
 * afnemer nodig heeft om hem tot de vastgelegde root te herleiden.
 *
 * Een instantie bestaat alleen als de sleutel bruikbaar is: P-256, met een certificaat dat erbij
 * hoort, door een ander uitgegeven en op dit moment geldig. Alles wat daaraan ontbreekt hoort de
 * start te blokkeren — een dienst die ondertekent met iets wat geen afnemer kan verifiëren, oogt
 * gezond en levert niets.
 */
class Ondertekensleutel private constructor(
    private val privateKey: ECPrivateKey,
    /** Ondertekencertificaat voorop, zonder de root: die legt een afnemer zelf vast. */
    val keten: List<X509Certificate>,
    /** Andere ondertekencertificaten in de keystore; staan tijdens een rotatie mee in de sleutelset. */
    val overige: List<X509Certificate>,
    val herkomst: Sleutelherkomst,
) {

    val certificaat: X509Certificate get() = keten.first()

    val kid: String = thumbprint(certificaat)

    /**
     * De OIN uit `subject.serialNumber` van het ondertekencertificaat, of `null` als die ontbreekt.
     * Dit is wat de uitgever in het document aan de sleutel bindt: zonder die band kan elk
     * certificaat onder dezelfde root een document ondertekenen dat zich als een ander voordoet.
     */
    val uitgeverOin: String? = oinUit(certificaat)

    /** De keten als `x5c`: DER in standaard base64, zoals RFC 7515 §4.1.6 voorschrijft. */
    val x5c: List<String> = keten.map(::alsX5c)

    /** Handtekening in de vorm die JWS vraagt: R‖S van 64 bytes, niet de DER-vorm van de JCA-default. */
    fun onderteken(gegevens: ByteArray): ByteArray = Signature.getInstance(JWS_HANDTEKENING).run {
        initSign(privateKey)
        update(gegevens)
        sign()
    }

    /**
     * Het moment waarop het eerste certificaat in de gepubliceerde keten verloopt. Een afnemer
     * toetst elk certificaat in `x5c`; een handtekening is dus niet langer bruikbaar dan de
     * kortst geldende schakel, ook als dat een tussencertificaat is.
     */
    val geldigTot: Instant = keten.minOf { it.notAfter.toInstant() }

    /** Tijd tot [geldigTot]; negatief als de keten al verlopen is. */
    fun resterend(nu: Instant): Duration = Duration.between(nu, geldigTot)

    companion object {
        private const val JWS_HANDTEKENING = "SHA256withECDSAinP1363Format"
        private const val COORDINAAT_BYTES = 32
        private const val OID_SERIALNUMBER = "2.5.4.5"
        private const val SERIALNUMBER = "SERIALNUMBER"

        /** Posities in de keyUsage-bitreeks (RFC 5280 §4.2.1.3). */
        private const val KEYUSAGE_DIGITAL_SIGNATURE = 0
        private const val KEYUSAGE_KEY_CERT_SIGN = 5

        private val BASE64URL = Base64.getUrlEncoder().withoutPadding()
        private val P256: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }

        fun uitKeystore(
            pad: Path,
            wachtwoord: CharArray,
            alias: String,
            nu: Instant,
            herkomst: Sleutelherkomst = Sleutelherkomst.KEYSTORE,
        ): Ondertekensleutel {
            val keystore = laad(pad, wachtwoord)
            val privateKey = leesSleutel(keystore, alias, wachtwoord, pad)
            val volledigeKeten = keystore.getCertificateChain(alias).orEmpty().map { it as X509Certificate }

            valideerCertificaat(volledigeKeten, privateKey, alias)
            valideerKeten(volledigeKeten, alias, nu)

            return Ondertekensleutel(
                privateKey = privateKey,
                keten = if (isZelfOndertekend(volledigeKeten.last())) volledigeKeten.dropLast(1) else volledigeKeten,
                overige = overigeOndertekencertificaten(keystore, volledigeKeten.first()),
                herkomst = herkomst,
            )
        }

        /**
         * Leest `subject.serialNumber` (OID 2.5.4.5), waar een organisatiecertificaat de OIN draagt.
         *
         * Alleen als het subject precies één zo'n attribuut heeft, als eigen enkelvoudige RDN. Bij
         * twee waarden of een samengestelde RDN kiest de ene bibliotheek de ene en de andere de
         * andere; een certificaat waarover afnemers het oneens kunnen zijn, heeft hier geen OIN.
         */
        fun oinUit(certificaat: X509Certificate): String? {
            val subject = certificaat.subjectX500Principal.getName(X500Principal.RFC2253, mapOf(OID_SERIALNUMBER to SERIALNUMBER))
            val rdns = LdapName(subject).rdns
            val dragers = rdns.filter { rdn -> rdn.toAttributes().iDs.asSequence().any { it.equals(SERIALNUMBER, ignoreCase = true) } }
            val enige = dragers.singleOrNull()?.takeIf { it.size() == 1 }

            return enige?.value?.toString()
        }

        /** De publieke sleutel van [certificaat] als JWK (RFC 7517), met zijn eigen keten-loze `x5c`. */
        fun jwk(certificaat: X509Certificate): Map<String, Any> {
            val punt = (certificaat.publicKey as ECPublicKey).w

            return linkedMapOf(
                "kty" to "EC",
                "crv" to "P-256",
                "x" to coordinaat(punt.affineX),
                "y" to coordinaat(punt.affineY),
                "kid" to thumbprint(certificaat),
                "use" to "sig",
                "alg" to "ES256",
                "x5c" to listOf(alsX5c(certificaat)),
            )
        }

        /**
         * RFC 7638: SHA-256 over de verplichte JWK-leden in lexicografische volgorde, zonder
         * witruimte. Met de hand opgebouwd omdat de volgorde en de afwezigheid van elk ander veld
         * hier het contract zijn.
         */
        private fun thumbprint(certificaat: X509Certificate): String {
            val punt = (certificaat.publicKey as ECPublicKey).w
            val json = """{"crv":"P-256","kty":"EC","x":"${coordinaat(punt.affineX)}","y":"${coordinaat(punt.affineY)}"}"""

            return BASE64URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(json.toByteArray()))
        }

        /** Vaste lengte van 32 bytes: `toByteArray` laat voorloopnullen weg of zet er een tekenbyte voor. */
        internal fun coordinaat(waarde: BigInteger): String {
            val ruw = waarde.toByteArray()
            val vast = ByteArray(COORDINAAT_BYTES)
            val lengte = minOf(ruw.size, COORDINAAT_BYTES)

            System.arraycopy(ruw, ruw.size - lengte, vast, COORDINAAT_BYTES - lengte, lengte)

            return BASE64URL.encodeToString(vast)
        }

        private fun alsX5c(certificaat: X509Certificate): String = Base64.getEncoder().encodeToString(certificaat.encoded)

        private fun laad(pad: Path, wachtwoord: CharArray): KeyStore {
            if (!Files.isReadable(pad)) {
                throw OngeldigeOndertekensleutelException("Keystore '$pad' bestaat niet of is niet leesbaar")
            }

            return try {
                KeyStore.getInstance("PKCS12").apply { Files.newInputStream(pad).use { load(it, wachtwoord) } }
            } catch (e: IOException) {
                throw OngeldigeOndertekensleutelException(
                    "Keystore '$pad' is niet te openen: verkeerd wachtwoord of geen PKCS#12-bestand",
                    e,
                )
            } catch (e: GeneralSecurityException) {
                throw OngeldigeOndertekensleutelException("Keystore '$pad' is niet te lezen", e)
            }
        }

        private fun leesSleutel(keystore: KeyStore, alias: String, wachtwoord: CharArray, pad: Path): ECPrivateKey {
            if (!keystore.isKeyEntry(alias)) {
                throw OngeldigeOndertekensleutelException("Keystore '$pad' bevat geen sleutel onder alias '$alias'")
            }

            val sleutel = try {
                keystore.getKey(alias, wachtwoord)
            } catch (e: GeneralSecurityException) {
                throw OngeldigeOndertekensleutelException("De sleutel onder alias '$alias' is niet te lezen", e)
            }

            if (sleutel !is ECPrivateKey || !isP256(sleutel)) {
                throw OngeldigeOndertekensleutelException(
                    "De sleutel onder alias '$alias' is geen EC P-256-sleutel (${sleutel.algorithm}); ES256 vraagt P-256",
                )
            }

            return sleutel
        }

        private fun valideerCertificaat(keten: List<X509Certificate>, privateKey: ECPrivateKey, alias: String) {
            val certificaat = keten.firstOrNull()
                ?: throw OngeldigeOndertekensleutelException("Alias '$alias' heeft geen certificaat")

            if (isZelfOndertekend(certificaat)) {
                throw OngeldigeOndertekensleutelException(
                    "Het certificaat onder alias '$alias' is zelfondertekend. Een afnemer herleidt de " +
                        "handtekening tot een vastgelegde root; daarvoor moet het certificaat door een ander zijn uitgegeven",
                )
            }

            if (!hoortBij(certificaat, privateKey)) {
                throw OngeldigeOndertekensleutelException("Het certificaat onder alias '$alias' hoort niet bij de sleutel")
            }

            if (certificaat.basicConstraints >= 0) {
                throw OngeldigeOndertekensleutelException(
                    "Het certificaat onder alias '$alias' is een CA-certificaat; ondertekenen hoort met een eindcertificaat",
                )
            }

            if (certificaat.keyUsage?.get(KEYUSAGE_DIGITAL_SIGNATURE) != true) {
                throw OngeldigeOndertekensleutelException(
                    "Het certificaat onder alias '$alias' mist het sleutelgebruik digitalSignature",
                )
            }
        }

        /**
         * Valideert de keten zoals een afnemer dat doet: padvalidatie volgens RFC 5280, op het
         * moment [nu]. Dat dekt wat een controle per schakel mist — een tussencertificaat dat geen
         * CA is, een overschreden padlengte, een verlopen schakel. Het bovenste certificaat uit de
         * keystore is hier het anker; een afnemer gebruikt daarvoor zijn eigen vastgelegde root.
         *
         * Padvalidatie toetst het anker zelf niet. Ontbreekt de root in de keystore, dan is het
         * anker een certificaat dat in `x5c` komt en dat een afnemer wél toetst; het krijgt hier
         * daarom dezelfde eisen als een uitgever.
         *
         * Zonder intrekkingscontrole: de keten kent geen CRL of OCSP.
         */
        internal fun valideerKeten(keten: List<X509Certificate>, alias: String, nu: Instant) {
            val certificaat = keten.first()

            if (nu.isBefore(certificaat.notBefore.toInstant()) || !nu.isBefore(certificaat.notAfter.toInstant())) {
                throw OngeldigeOndertekensleutelException(
                    "Het certificaat onder alias '$alias' is niet geldig op $nu " +
                        "(geldig van ${certificaat.notBefore.toInstant()} tot ${certificaat.notAfter.toInstant()})",
                )
            }

            // Alleen het eindcertificaat in de keystore: er is geen uitgever om tegen te valideren.
            if (keten.size == 1) return

            valideerUitgever(keten.last(), alias, nu)

            val parameters = PKIXParameters(setOf(TrustAnchor(keten.last(), null))).apply {
                isRevocationEnabled = false
                date = Date.from(nu)
            }

            try {
                val pad = CertificateFactory.getInstance("X.509").generateCertPath(keten.dropLast(1))

                CertPathValidator.getInstance("PKIX").validate(pad, parameters)
            } catch (e: GeneralSecurityException) {
                throw OngeldigeOndertekensleutelException(
                    "De certificaatketen onder alias '$alias' sluit niet of is niet geldig op $nu: ${e.message}",
                    e,
                )
            }
        }

        private fun valideerUitgever(uitgever: X509Certificate, alias: String, nu: Instant) {
            val naam = uitgever.subjectX500Principal.name

            if (uitgever.basicConstraints < 0 || uitgever.keyUsage?.get(KEYUSAGE_KEY_CERT_SIGN) == false) {
                throw OngeldigeOndertekensleutelException(
                    "Het bovenste certificaat in de keten onder alias '$alias' ($naam) mag geen certificaten uitgeven",
                )
            }

            if (nu.isBefore(uitgever.notBefore.toInstant()) || !nu.isBefore(uitgever.notAfter.toInstant())) {
                throw OngeldigeOndertekensleutelException(
                    "Het bovenste certificaat in de keten onder alias '$alias' ($naam) is niet geldig op $nu",
                )
            }
        }

        /** Proefondertekening: de enige manier om zonder de sleutel te exporteren te zien dat het paar klopt. */
        private fun hoortBij(certificaat: X509Certificate, privateKey: ECPrivateKey): Boolean {
            val publiek = certificaat.publicKey

            if (publiek !is ECPublicKey || !isP256(publiek)) return false

            val proef = "stelseldocument".toByteArray()
            val handtekening = Signature.getInstance(JWS_HANDTEKENING).run {
                initSign(privateKey)
                update(proef)
                sign()
            }

            return Signature.getInstance(JWS_HANDTEKENING).run {
                initVerify(publiek)
                update(proef)
                verify(handtekening)
            }
        }

        private fun overigeOndertekencertificaten(keystore: KeyStore, actief: X509Certificate): List<X509Certificate> =
            keystore.aliases().toList()
                .mapNotNull { keystore.getCertificate(it) as? X509Certificate }
                .filter { it != actief && it.basicConstraints < 0 }
                .filter { (it.publicKey as? ECPublicKey)?.let(::isP256) == true }
                .distinct()

        /** Op naam én op handtekening: een gelijke naam alleen maakt een certificaat nog geen root. */
        private fun isZelfOndertekend(certificaat: X509Certificate): Boolean =
            certificaat.subjectX500Principal == certificaat.issuerX500Principal &&
                runCatching { certificaat.verify(certificaat.publicKey) }.isSuccess

        private fun isP256(sleutel: ECKey): Boolean =
            sleutel.params.curve == P256.curve && sleutel.params.order == P256.order
    }
}
