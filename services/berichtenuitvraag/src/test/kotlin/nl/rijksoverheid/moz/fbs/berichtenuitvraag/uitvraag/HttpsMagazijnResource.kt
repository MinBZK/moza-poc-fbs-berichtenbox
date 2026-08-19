package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Een magazijn achter TLS, met een certificaat dat de JVM-default trust-store níét kent —
 * de situatie op ZAD, waar de FSC-outway zijn poort serveert met een cert uit de interne PKI
 * van de peer. Bewijst dat het anker uit `quarkus.tls.outway.*` daadwerkelijk tot een
 * geslaagde handshake leidt, en niet alleen tot een aangeroepen setter.
 *
 * Het sleutelmateriaal wordt bij elke run vers gemaakt met `keytool` uit de draaiende JDK, in
 * plaats van als fixture in de repo te staan. Twee redenen: een ingecheckte private sleutel is
 * er één die scanners en lezers moeten leren negeren, en een ingecheckt certificaat verloopt
 * ooit — precies lang genoeg na nu om de build op een willekeurige dag te breken.
 */
class HttpsMagazijnResource : QuarkusTestResourceLifecycleManager {

    companion object {
        const val OIN = WireMockBackendsResource.OIN_A

        private const val WACHTWOORD = "changeit"

        lateinit var magazijn: WireMockServer
    }

    private var server: WireMockServer? = null
    private lateinit var werkmap: Path

    override fun start(): Map<String, String> {
        werkmap = Files.createTempDirectory("outway-tls-test")

        val keystore = werkmap.resolve("magazijn.p12")
        val certPem = werkmap.resolve("magazijn.pem")

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

        val gestart = WireMockServer(
            wireMockConfig()
                .dynamicPort()
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
            "quarkus.tls.outway.trust-store.pem.certs" to certPem.toString(),
        )
    }

    override fun stop() {
        server?.stop()

        if (::werkmap.isInitialized) {
            werkmap.toFile().deleteRecursively()
        }
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

        val uitvoer = proces.inputStream.bufferedReader().readText()

        check(proces.waitFor() == 0) { "keytool ${args.first()} faalde: $uitvoer" }
    }
}
