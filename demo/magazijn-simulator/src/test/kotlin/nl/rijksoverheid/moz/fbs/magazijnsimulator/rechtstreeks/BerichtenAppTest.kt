package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.APP_MANAGER_OIN
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.KID
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.MAGAZIJN_A
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.MAGAZIJN_C
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.ONTVANGER
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.appManager
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.basis
import org.jose4j.json.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * De browsercode van de Berichten-app (`demo/berichten-app`) haalt onder Node op bij twee
 * gesimuleerde magazijnen en één onbekend magazijn, en voegt samen. Zo toetst de test de hele keten
 * van de tweede ingang tussen `jose` op WebCrypto en de simulator: credential, presentatie, sessie
 * en versleutelde lijst.
 */
@QuarkusTest
@TestProfile(RechtstreeksTest.Profiel::class)
class BerichtenAppTest : MagazijnTestBasis() {

    @Inject
    lateinit var klok: Clock

    @TestHTTPResource("/")
    lateinit var server: URL

    @Test
    fun `de Berichten-app haalt bij elk magazijn op, voegt samen en noemt het magazijn dat niet antwoordt`() {
        // Verweven over de twee magazijnen: alleen samenvoegen op tijdstip geeft deze volgorde.
        val nu = klok.instant()

        leverAan(MAGAZIJN_C, "Bij C", nu.minus(Duration.ofHours(3)))
        leverAan(MAGAZIJN_A, "Bij A", nu.minus(Duration.ofHours(2)))
        leverAan(MAGAZIJN_C, "Ook bij C", nu.minus(Duration.ofHours(1)))

        val magazijnen = listOf(MAGAZIJN_A to "Magazijn A", MAGAZIJN_C to "Magazijn C", ONBEKEND to "Onbekend").map { (oin, naam) ->
            mapOf("oin" to oin, "naam" to naam, "url" to "${server.toString().trimEnd('/')}/magazijn/$oin")
        }

        val sleutel = Base64.getEncoder().encodeToString(appManager().private.encoded)

        val uitvoer = node("--import", HAAK, SCRIPT, ObjectMapper().writeValueAsString(magazijnen), sleutel, APP_MANAGER_OIN, KID, ONTVANGER, "${klok.instant().epochSecond}")
        val resultaat = JsonUtil.parseJson(uitvoer.lines().last { it.isNotBlank() })
        val berichten = resultaat["berichten"] as List<*>

        assertEquals(listOf("Ook bij C", "Bij A", "Bij C"), berichten.map { (it as Map<*, *>)["onderwerp"] })
        assertEquals(listOf("Magazijn C", "Magazijn A", "Magazijn C"), berichten.map { (it as Map<*, *>)["magazijn"] })
        assertEquals(listOf(mapOf("oin" to ONBEKEND, "reden" to "gaf een fout (404)")), resultaat["mislukt"])
    }

    private fun leverAan(magazijn: String, onderwerp: String, publicatietijdstip: Instant) {
        val (type, waarde) = ONTVANGER.split(':')

        given().contentType(ContentType.JSON)
            .body(
                """{"afzender": "$magazijn", "ontvanger": {"type": "$type", "waarde": "$waarde"}, "onderwerp": "$onderwerp", """ +
                    """"inhoud": "Inhoud.", "publicatietijdstip": "$publicatietijdstip"}""",
            )
            .post("${basis(magazijn)}/aanleveringen")
            .then().statusCode(201)
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
        const val ONBEKEND = "00000009000000009999"
        const val TIJDSLIMIET_SECONDEN = 60L
        val SCRIPT: String = Path.of("../berichten-app/src/test/js/tegen-simulator.mjs").toAbsolutePath().normalize().toString()

        /** Laat de browsercode jose uit `node_modules` van de Berichten-app laden; die map vult haar build (`npm ci`). */
        val HAAK: String = Path.of("../berichten-app/src/test/js/jose-uit-npm.mjs").toAbsolutePath().normalize().toUri().toString()
    }
}
