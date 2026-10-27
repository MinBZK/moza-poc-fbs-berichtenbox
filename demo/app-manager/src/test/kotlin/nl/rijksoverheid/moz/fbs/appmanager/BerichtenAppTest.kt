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
    fun `de Berichten-app activeert met een niet-exporteerbare sleutel en haalt twee keer een credential op`() {
        val uitvoer = node(SCRIPT, basis.toString(), "https://app-manager.test/", AppManagerConfig.DEMO_OIN, "vandijk")
        val resultaat = JsonUtil.parseJson(uitvoer.lines().last { it.isNotBlank() })

        assertEquals("https://app.test/", resultaat["terug"])

        listOf("credential", "vers").forEach { veld ->
            val claims = TestApp.verifieerJws(resultaat[veld] as String, sleutel.publiek)

            assertEquals("KVK:90000014", claims["ontvanger"], veld)
            assertEquals(AppManagerConfig.DEMO_OIN, claims["iss"], veld)
        }

        assertEquals(resultaat["jwk"], resultaat["cnf"])
    }

    private fun node(vararg argumenten: String): String {
        val log = Files.createTempFile("node", ".log")

        // Naar een bestand, niet naar een pipe: een volle pipe-buffer laat node blokkeren tot de
        // tijdslimiet afloopt.
        val proces = ProcessBuilder(listOf("node") + argumenten).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val klaar = proces.waitFor(TIJDSLIMIET_SECONDEN, TimeUnit.SECONDS)

        if (!klaar) proces.destroyForcibly()

        val uitvoer = Files.readString(log)

        Files.delete(log)

        assertTrue(klaar && proces.exitValue() == 0, "node faalde:\n$uitvoer")

        return uitvoer
    }

    private companion object {
        const val TIJDSLIMIET_SECONDEN = 60L
        val SCRIPT: String = Path.of("../berichten-app/src/test/js/tegen-app-manager.mjs").toAbsolutePath().normalize().toString()
    }
}
