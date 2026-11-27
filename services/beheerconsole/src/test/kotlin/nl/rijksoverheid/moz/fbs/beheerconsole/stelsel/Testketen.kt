package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Een root en een ondertekencertificaat daaronder, gemaakt met `keytool` zoals het stelselregister
 * dat in zijn tests doet. Eén keer per keten per JVM: elke keten kost een paar keytool-aanroepen.
 */
class Testketen private constructor(val root: X509Certificate, val certificaat: X509Certificate, val sleutel: PrivateKey) {

    val rootPem: String
        get() = "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(root.encoded) +
            "\n-----END CERTIFICATE-----\n"

    /** Een stelseldocument, ondertekend met deze keten; [header] en [payload] overschrijven velden. */
    fun document(
        uitgegeven: Instant,
        geldigheidSeconden: Long = 3600,
        uitgever: String = OIN,
        omgeving: String = OMGEVING,
        organisaties: List<Triple<String, String, String>> = listOf(Triple("00000001003214345000", "Organisatie A", "https://a.example/")),
        extraHeader: Map<String, String> = emptyMap(),
    ): String {
        val orgs = organisaties.joinToString(",") { (oin, naam, url) ->
            """{"oin":"$oin","name":"$naam","magazijn_url":"$url"}"""
        }
        val jws = JsonWebSignature().apply {
            payload = """{"iss":"$uitgever","iat":${uitgegeven.epochSecond},"exp":${uitgegeven.epochSecond + geldigheidSeconden},""" +
                """"version":"v1","environment":"$omgeving","organizations":[$orgs],"app_managers":[],"document_types":["bericht"]}"""
            algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
            setHeader("typ", "stelseldocument+jwt")
            keyIdHeaderValue = "test-kid"
            setCertificateChainHeaderValue(certificaat)
            extraHeader.forEach { (naam, waarde) -> setHeader(naam, waarde) }
            key = sleutel
        }

        return jws.compactSerialization
    }

    companion object {
        const val OIN = "00000000000000001000"

        const val OMGEVING = "test"

        val geldig: Testketen by lazy { maak("geldig", OIN) }

        val ander: Testketen by lazy { maak("ander", OIN) }

        val andereUitgever: Testketen by lazy { maak("uitgever", "00000000000000007777") }

        /**
         * Eén bestand per keten per JVM. Een `QuarkusTestProfile` en de test zelf laden deze klasse in
         * verschillende classloaders; met een keten per classloader vertrouwt de dienst een andere
         * root dan waarmee de test ondertekent.
         */
        private fun maak(naam: String, oin: String): Testketen {
            val pad = Path.of(System.getProperty("java.io.tmpdir"), "beheerconsole-testketen-$naam-${ProcessHandle.current().pid()}.p12")
            val wachtwoord = "wachtwoord-$naam"

            if (Files.notExists(pad)) bouw(pad, wachtwoord, naam, oin)

            val keystore = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(pad).use { load(it, wachtwoord.toCharArray()) } }

            return Testketen(
                keystore.getCertificate("root") as X509Certificate,
                keystore.getCertificate("blad") as X509Certificate,
                keystore.getKey("blad", wachtwoord.toCharArray()) as PrivateKey,
            )
        }

        private fun bouw(pad: Path, wachtwoord: String, naam: String, oin: String) {
            pad.toFile().deleteOnExit()
            val opslag = listOf("-keystore", pad.toString(), "-storetype", "PKCS12", "-storepass", wachtwoord)
            val p256 = listOf("-keyalg", "EC", "-groupname", "secp256r1")

            keytool(
                listOf("-genkeypair", "-alias", "root", "-dname", "CN=Testroot $naam", "-validity", "30", "-startdate", "-1d") +
                    p256 + listOf("-ext", "bc:c=ca:true", "-ext", "ku:c=keyCertSign") + opslag,
            )
            keytool(
                listOf("-genkeypair", "-alias", "blad", "-signer", "root", "-dname", "CN=Test-ondertekenaar, SERIALNUMBER=$oin") +
                    listOf("-validity", "20", "-startdate", "-1d") + p256 + listOf("-ext", "ku:c=digitalSignature") + opslag,
            )
        }

        private fun keytool(argumenten: List<String>) {
            val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val proces = ProcessBuilder(listOf(keytool) + argumenten).redirectErrorStream(true).start()
            val uitvoer = proces.inputStream.bufferedReader().readText()

            check(proces.waitFor(60, TimeUnit.SECONDS) && proces.exitValue() == 0) { "keytool faalde: $uitvoer" }
        }
    }
}
