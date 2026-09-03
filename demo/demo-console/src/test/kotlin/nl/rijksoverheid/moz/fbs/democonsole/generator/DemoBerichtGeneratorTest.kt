package nl.rijksoverheid.moz.fbs.democonsole.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

class DemoBerichtGeneratorTest {

    private val rvo = "00000000000000100000"
    private val belastingdienst = "00000001823288444000"

    private val organisaties = mapOf(
        rvo to Organisatie(
            rvo,
            "RVO",
            listOf(Sjabloon("Subsidie", "Uw subsidie is toegekend."), Sjabloon("Beschikking", "De beschikking is klaar.")),
        ),
        belastingdienst to Organisatie(
            belastingdienst,
            "Belastingdienst",
            listOf(Sjabloon("Aanslag", "Uw aanslag staat klaar."), Sjabloon("Teruggaaf", "U ontvangt een teruggaaf.")),
        ),
    )

    private val personas = listOf(
        Persona("pietersen", "J. Pietersen", "BSN", "999993653", listOf(rvo, belastingdienst)),
        Persona("bakkerij", "Bakkerij De Vroege Vogel", "BSN", "999996666", listOf(rvo)),
        Persona("vandijk", "Garage Van Dijk B.V.", "KVK", "12345678", listOf(belastingdienst)),
    )

    private val klok = Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC)

    private fun generator() = DemoBerichtGenerator(personas, organisaties, klok)

    @Test
    fun `genereert exact het gevraagde aantal opdrachten`() {
        assertEquals(25, generator().genereer(aantal = 25, random = Random(1)).size)
    }

    @Test
    fun `afzender is altijd de OIN van het doelmagazijn`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(2))

        assertTrue(opdrachten.all { it.verzoek.afzender == it.magazijnOin })
    }

    @Test
    fun `elke ontvanger ontvangt van het gekozen magazijn (opt-in klopt)`() {
        val opdrachten = generator().genereer(aantal = 100, random = Random(3))
        val optIn = personas.associate { (it.type to it.waarde) to it.magazijnen.toSet() }

        assertTrue(
            opdrachten.all { it.magazijnOin in optIn.getValue(it.verzoek.ontvanger.type to it.verzoek.ontvanger.waarde) },
            "elk bericht moet naar een persona gaan die bij dat magazijn opt-in staat",
        )
    }

    @Test
    fun `Bakkerij ontvangt alleen van RVO, Garage alleen van Belastingdienst`() {
        val opdrachten = generator().genereer(aantal = 100, random = Random(4))

        val bakkerij = opdrachten.filter { it.verzoek.ontvanger.waarde == "999996666" }
        val garage = opdrachten.filter { it.verzoek.ontvanger.waarde == "12345678" }

        assertTrue(bakkerij.isNotEmpty() && bakkerij.all { it.magazijnOin == rvo })
        assertTrue(garage.isNotEmpty() && garage.all { it.magazijnOin == belastingdienst })
    }

    @Test
    fun `onderwerp is een onderwerp van de afzendende organisatie en valt binnen de contractgrenzen`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(5))

        assertTrue(opdrachten.all { it.verzoek.onderwerp.length in 1..255 })
        assertTrue(
            opdrachten.all { opdracht ->
                organisaties.getValue(opdracht.magazijnOin).sjablonen.any { it.onderwerp == opdracht.verzoek.onderwerp }
            },
            "onderwerp moet uit een sjabloon van de afzendende organisatie komen",
        )
    }

    @Test
    fun `publicatietijdstip ligt in het verleden en is ISO-8601 met Z`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(6))
        val nu = Instant.parse("2026-07-01T12:00:00Z")

        assertTrue(opdrachten.all { it.verzoek.publicatietijdstip.endsWith("Z") })
        assertTrue(opdrachten.all { Instant.parse(it.verzoek.publicatietijdstip).isBefore(nu) })
    }

    @Test
    fun `varieert over personas, organisaties en sjablonen`() {
        // Alle andere assertions hebben de vorm "elke opdracht voldoet aan P" en blijven groen bij
        // een regressie naar personas[0]. Deze pint dat er daadwerkelijk gespreid wordt.
        val opdrachten = generator().genereer(aantal = 100, random = Random(8))

        assertEquals(personas.size, opdrachten.map { it.verzoek.ontvanger.waarde }.distinct().size)
        assertEquals(organisaties.size, opdrachten.map { it.magazijnOin }.distinct().size)
        assertEquals(
            organisaties.values.sumOf { it.sjablonen.size },
            opdrachten.map { it.verzoek.onderwerp }.distinct().size,
            "elk sjabloon van elke organisatie hoort voor te komen",
        )
        assertTrue(opdrachten.map { it.verzoek.publicatietijdstip }.distinct().size > 1, "tijdstippen moeten spreiden")
    }

    @Test
    fun `zelfde seed geeft identieke uitvoer`() {
        assertEquals(
            generator().genereer(aantal = 20, random = Random(42)),
            generator().genereer(aantal = 20, random = Random(42)),
        )
    }

    @Test
    fun `aantal nul geeft een lege lijst`() {
        assertTrue(generator().genereer(aantal = 0, random = Random(7)).isEmpty())
    }

    @Test
    fun `een persona met een dubbele id faalt fail-fast`() {
        // De id is waarmee het paneel er één aanwijst; de tweede zou onbereikbaar zijn zonder dat
        // iets dat meldt.
        val ongeldig = personas + Persona("bakkerij", "Andere Bakkerij", "BSN", "999993653", listOf(rvo))

        assertThrows(IllegalArgumentException::class.java) {
            DemoBerichtGenerator(ongeldig, organisaties, klok)
        }
    }

    @Test
    fun `de doelgroep draagt elke persona met id en label`() {
        assertEquals(
            listOf(
                Doelpersona("pietersen", "J. Pietersen"),
                Doelpersona("bakkerij", "Bakkerij De Vroege Vogel"),
                Doelpersona("vandijk", "Garage Van Dijk B.V."),
            ),
            generator().doelgroep(),
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 25])
    fun `genereerVoor levert exact het gevraagde aantal voor de gekozen persona`(aantal: Int) {
        // Ook 0 en 1: bij 1 verbergt "alle opdrachten horen bij deze persona" een regressie naar
        // "geeft de eerste persona terug", en 0 hoort een lege lijst te zijn en geen fout.
        val opdrachten = generator().genereerVoor("bakkerij", aantal, Random(11))!!

        assertEquals(aantal, opdrachten.size)
        assertTrue(opdrachten.all { it.verzoek.ontvanger.waarde == "999996666" })
    }

    @Test
    fun `genereerVoor kiest per persona en niet de eerste uit de lijst`() {
        val vandijk = generator().genereerVoor("vandijk", 10, Random(12))!!

        assertTrue(vandijk.all { it.verzoek.ontvanger.type == "KVK" && it.verzoek.ontvanger.waarde == "12345678" })
        assertTrue(vandijk.all { it.magazijnOin == belastingdienst })
    }

    @Test
    fun `genereerVoor blijft binnen de magazijnen van de persona en gebruikt ze allemaal`() {
        // Pietersen staat bij beide organisaties opt-in; een implementatie die altijd het eerste
        // magazijn pakt zou hier stil doorheen komen zonder de tweede assertie.
        val opdrachten = generator().genereerVoor("pietersen", 100, Random(13))!!

        assertTrue(opdrachten.all { it.magazijnOin in setOf(rvo, belastingdienst) })
        assertEquals(setOf(rvo, belastingdienst), opdrachten.map { it.magazijnOin }.toSet())
        assertTrue(opdrachten.all { it.verzoek.afzender == it.magazijnOin })
    }

    @Test
    fun `genereerVoor met een onbekende persona geeft null zodat de aanroeper een 404 kan geven`() {
        assertNull(generator().genereerVoor("bestaat-niet", 1, Random(14)))
    }

    @Test
    fun `genereerVoor met dezelfde seed geeft identieke uitvoer`() {
        assertEquals(
            generator().genereerVoor("pietersen", 20, Random(42)),
            generator().genereerVoor("pietersen", 20, Random(42)),
        )
    }

    @Test
    fun `een persona met ongeldige elfproef faalt fail-fast bij constructie`() {
        val ongeldig = listOf(Persona("fout", "Fout", "BSN", "999993654", listOf(rvo)))

        assertThrows(IllegalArgumentException::class.java) {
            DemoBerichtGenerator(ongeldig, organisaties, klok)
        }
    }

    @Test
    fun `een persona met een onbekende organisatie-OIN faalt fail-fast`() {
        val ongeldig = listOf(Persona("fout", "Fout", "BSN", "999993653", listOf("00000000000000000000")))

        assertThrows(IllegalArgumentException::class.java) {
            DemoBerichtGenerator(ongeldig, organisaties, klok)
        }
    }

    @Test
    fun `een persona zonder magazijnen faalt fail-fast`() {
        val ongeldig = listOf(Persona("fout", "Fout", "BSN", "999993653", emptyList()))

        assertThrows(IllegalArgumentException::class.java) {
            DemoBerichtGenerator(ongeldig, organisaties, klok)
        }
    }
}
