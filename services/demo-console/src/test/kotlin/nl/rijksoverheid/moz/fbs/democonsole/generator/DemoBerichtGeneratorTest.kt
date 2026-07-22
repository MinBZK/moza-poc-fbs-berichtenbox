package nl.rijksoverheid.moz.fbs.democonsole.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

class DemoBerichtGeneratorTest {

    private val afzenderOin = "00000001003214345000"
    private val magazijnen = listOf("00000001003214345000", "00000001823288444000")

    private val personas = listOf(
        Persona("J. Pietersen", "BSN", "999993653"),
        Persona("Bakkerij De Vroege Vogel", "BSN", "123456782"),
        Persona("Garage Van Dijk B.V.", "KVK", "12345678"),
    )

    private val klok = Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC)

    private fun generator() = DemoBerichtGenerator(personas, afzenderOin, magazijnen, klok)

    @Test
    fun `genereert exact het gevraagde aantal opdrachten`() {
        val opdrachten = generator().genereer(aantal = 25, random = Random(1))

        assertEquals(25, opdrachten.size)
    }

    @Test
    fun `alle opdrachten gebruiken de geautoriseerde afzender-OIN`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(2))

        assertTrue(opdrachten.all { it.verzoek.afzender == afzenderOin })
    }

    @Test
    fun `elke ontvanger is een van de personas`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(3))
        val toegestaan = personas.map { it.type to it.waarde }.toSet()

        assertTrue(opdrachten.all { (it.verzoek.ontvanger.type to it.verzoek.ontvanger.waarde) in toegestaan })
    }

    @Test
    fun `elk magazijn is een van de geregistreerde OIN's`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(4))

        assertTrue(opdrachten.all { it.magazijnOin in magazijnen })
    }

    @Test
    fun `onderwerp en inhoud vallen binnen de contractgrenzen`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(5))

        assertTrue(opdrachten.all { it.verzoek.onderwerp.length in 1..255 })
        assertTrue(opdrachten.all { it.verzoek.inhoud.isNotEmpty() && it.verzoek.inhoud.length < 10_000 })
    }

    @Test
    fun `publicatietijdstip ligt in het verleden en is ISO-8601 met Z`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(6))
        val nu = Instant.parse("2026-07-01T12:00:00Z")

        assertTrue(opdrachten.all { it.verzoek.publicatietijdstip.endsWith("Z") })
        assertTrue(opdrachten.all { Instant.parse(it.verzoek.publicatietijdstip).isBefore(nu) })
    }

    @Test
    fun `zelfde seed geeft identieke uitvoer`() {
        val a = generator().genereer(aantal = 20, random = Random(42))
        val b = generator().genereer(aantal = 20, random = Random(42))

        assertEquals(a, b)
    }

    @Test
    fun `aantal nul geeft een lege lijst`() {
        val opdrachten = generator().genereer(aantal = 0, random = Random(7))

        assertTrue(opdrachten.isEmpty())
    }

    @Test
    fun `bij meerdere berichten worden meerdere personas geraakt`() {
        val ontvangers = generator().genereer(aantal = 40, random = Random(8))
            .map { it.verzoek.ontvanger.waarde }
            .toSet()

        assertTrue(ontvangers.size >= 2, "verwacht spreiding over personas, kreeg: $ontvangers")
    }

    @Test
    fun `een persona met ongeldige elfproef faalt fail-fast bij constructie`() {
        val ongeldig = listOf(Persona("Fout", "BSN", "999993654"))

        assertThrows(IllegalArgumentException::class.java) {
            DemoBerichtGenerator(ongeldig, afzenderOin, magazijnen, klok)
        }
    }

    @Test
    fun `lege persona-lijst faalt fail-fast bij constructie`() {
        assertThrows(IllegalArgumentException::class.java) {
            DemoBerichtGenerator(emptyList(), afzenderOin, magazijnen, klok)
        }
    }
}
