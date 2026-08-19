package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import nl.rijksoverheid.moz.fbs.common.fsc.OutwayTls
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Een magazijn achter TLS met een certificaat dat de JVM-default trust-store níét kent — de
 * situatie op ZAD, waar de FSC-outway zijn poort serveert met een cert uit de interne PKI van de
 * peer. Bewijst dat het anker uit `quarkus.tls.<naam>.*` daadwerkelijk tot een geslaagde
 * handshake leidt, en niet alleen tot een aangeroepen setter.
 *
 * De inschrijving krijgt een grant-hash, want dat is de combinatie die op ZAD bestaat: hetzelfde
 * outway-adres draagt TLS én de FSC-headers, en de grant-hash is tegelijk de vlag waarop het
 * anker geselecteerd wordt. De tegenhanger — een magazijn zónder grant-hash krijgt het anker niet
 * — is niet met een echte handshake te tonen (dat vraagt een publiek vertrouwd certificaat) en
 * staat daarom als unit-test in `MagazijnClientFactoryOutwayTlsTest`.
 *
 * Het sleutelmateriaal wordt bij elke run vers gemaakt met `keytool` uit de draaiende JDK, in
 * plaats van als fixture in de repo te staan. Twee redenen: een ingecheckte private sleutel is er
 * één die scanners en lezers moeten leren negeren, en een ingecheckt certificaat verloopt ooit —
 * precies lang genoeg na nu om de build op een willekeurige dag te breken.
 */
class HttpsMagazijnResource : QuarkusTestResourceLifecycleManager {

    companion object {
        const val OIN = WireMockBackendsResource.OIN_A
        const val GRANT_HASH = "\$1\$3\$test-grant-hash"

        private const val WACHTWOORD = "changeit"
        private const val KEYTOOL_TIMEOUT_SECONDEN = 30L

        lateinit var magazijn: WireMockServer
    }

    private var server: WireMockServer? = null
    private var werkmap: Path? = null

    override fun start(): Map<String, String> {
        val map = Files.createTempDirectory("outway-tls-test")
        werkmap = map

        // Opruimen bij een mislukte start: anders blijft er sleutelmateriaal achter, want stop()
        // wordt niet aangeroepen als start() gooit.
        return try {
            startMet(map)
        } catch (fout: Exception) {
            opruimen()

            throw fout
        }
    }

    private fun startMet(map: Path): Map<String, String> {
        val keystore = map.resolve("magazijn.p12")
        val certPem = map.resolve("magazijn.pem")

        // CN + SAN op localhost: de REST-client verifieert de hostnaam, dus een cert zonder
        // passende SAN zou hier falen op iets anders dan waar deze test over gaat.
        keytool(
            "-genkeypair", "-alias", "magazijn", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-validity", "1", "-storetype", "PKCS12",
            "-keystore", keystore.toString(), "-storepass", WACHTWOORD,
        )

        keytool(
            "-exportcert", "-rfc", "-alias", "magazijn",
            "-keystore", keystore.toString(), "-storepass", WACHTWOORD,
            "-file", certPem.toString(),
        )

        // httpDisabled: zonder plaintext-poort kan de call niet alsnog buiten TLS om slagen,
        // wat de bewijskracht van deze fixture is.
        val gestart = WireMockServer(
            wireMockConfig()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(keystore.toString())
                .keystorePassword(WACHTWOORD)
                .keyManagerPassword(WACHTWOORD)
                .keystoreType("PKCS12"),
        )

        gestart.start()
        server = gestart
        magazijn = gestart

        return mapOf(
            "magazijnen.\"$OIN\".url" to "https://localhost:${gestart.httpsPort()}",
            "magazijnen.\"$OIN\".grantHash" to GRANT_HASH,
            "quarkus.tls.${OutwayTls.CONFIG_NAAM}.trust-store.pem.certs" to certPem.toString(),
        )
    }

    override fun stop() {
        server?.stop()
        opruimen()
    }

    private fun opruimen() {
        werkmap?.toFile()?.deleteRecursively()
        werkmap = null
    }

    /**
     * `keytool` uit de JDK die deze test draait, niet uit `$PATH`: een build-machine hoeft geen
     * JDK op het pad te hebben, en een andere keytool dan de draaiende JVM zou een keystore
     * kunnen schrijven die deze JVM niet leest.
     */
    private fun keytool(vararg args: String) {
        val binair = Paths.get(System.getProperty("java.home"), "bin", "keytool").toString()
        val proces = ProcessBuilder(listOf(binair) + args)
            .redirectErrorStream(true)
            .start()

        // Met timeout: een keytool die op een trage entropiebron blijft hangen zou de build
        // anders zonder enig signaal laten staan tot de CI-job zelf afkapt.
        if (!proces.waitFor(KEYTOOL_TIMEOUT_SECONDEN, TimeUnit.SECONDS)) {
            proces.destroyForcibly()

            error("keytool ${args.first()} reageerde niet binnen $KEYTOOL_TIMEOUT_SECONDEN seconden")
        }

        val uitvoer = proces.inputStream.bufferedReader().readText()

        check(proces.exitValue() == 0) { "keytool ${args.first()} faalde: $uitvoer" }
    }
}
