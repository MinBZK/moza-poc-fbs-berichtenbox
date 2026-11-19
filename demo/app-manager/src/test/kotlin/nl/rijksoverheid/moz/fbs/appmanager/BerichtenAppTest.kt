package nl.rijksoverheid.moz.fbs.appmanager

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.rechtstreeks.TestApp
import org.jose4j.json.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * De browsercode van de Berichten-app (`demo/berichten-app`) tegen deze App Manager, onder Node.
 * Zo toetst een tweede implementatie van JWS en JWE (`jose` op WebCrypto) de Verifier-library,
 * met dezelfde regels die een browser volgt, zoals een sleutel die niet te exporteren is.
 */
@QuarkusTest
class BerichtenAppTest {

    @Inject
    lateinit var sleutel: Ondertekensleutel

    @TestHTTPResource("/")
    lateinit var basis: URL

    @Test
    fun `de Berichten-app activeert met een niet-exporteerbare sleutel, haalt twee keer een credential op en vergeet een onbekende registratie`() {
        val uitvoer = node("--import", HAAK, SCRIPT, basis.toString(), "https://app-manager.test/", AppManagerConfig.DEMO_OIN, "vandijk")
        val resultaat = JsonUtil.parseJson(uitvoer.lines().last { it.isNotBlank() })

        assertEquals("https://app.test/", resultaat["terug"])
        assertEquals(true, resultaat["onbekendVergeten"], "een registratie die de App Manager niet kent, blijft staan")

        listOf("credential", "vers").forEach { veld ->
            val claims = TestApp.verifieerJws(resultaat[veld] as String, sleutel.publiek)

            assertEquals("KVK:90000014", claims["ontvanger"], veld)
            assertEquals(AppManagerConfig.DEMO_OIN, claims["iss"], veld)
            // Beide credentials horen bij de sleutel van de app, ook het verse uit een nieuwe sessie.
            assertEquals(resultaat["jwk"], (claims["cnf"] as Map<*, *>)["jwk"], veld)
            assertTrue((claims["exp"] as Number).toLong() > Instant.now().epochSecond, "$veld is al verlopen")
        }
    }

    private fun node(vararg argumenten: String): String {
        val log = Files.createTempFile("node", ".log")

        // Naar een bestand, niet naar een pipe: een volle pipe-buffer laat node blokkeren tot de
        // tijdslimiet afloopt.
        val proces = ProcessBuilder(listOf("node") + argumenten).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val klaar = proces.waitFor(TIJDSLIMIET_SECONDEN, TimeUnit.SECONDS)

        // `node --test` start per bestand een eigen proces; die moeten mee, anders blijven ze draaien.
        if (!klaar) {
            proces.descendants().forEach { it.destroyForcibly() }
            proces.destroyForcibly()
        }

        val uitvoer = Files.readString(log)

        Files.delete(log)

        assertTrue(klaar && proces.exitValue() == 0, "node faalde:\n$uitvoer")

        return uitvoer
    }

    private companion object {
        const val TIJDSLIMIET_SECONDEN = 60L
        val SCRIPT: String = Path.of("../berichten-app/src/test/js/tegen-app-manager.mjs").toAbsolutePath().normalize().toString()

        /**
         * Laat de browsercode jose uit `node_modules` van de Berichten-app laden. Die map vult de build
         * van de Berichten-app (`npm ci`); de test-dependency op die module in de pom zet haar
         * daarom eerder in de reactor.
         */
        val HAAK: String = Path.of("../berichten-app/src/test/js/jose-uit-npm.mjs").toAbsolutePath().normalize().toUri().toString()
    }
}
