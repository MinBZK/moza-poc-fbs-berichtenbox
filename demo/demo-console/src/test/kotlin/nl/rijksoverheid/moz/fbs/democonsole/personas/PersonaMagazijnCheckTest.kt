package nl.rijksoverheid.moz.fbs.democonsole.personas

import nl.rijksoverheid.moz.fbs.demopersonas.DemoPersona
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaBron
import nl.rijksoverheid.moz.fbs.demopersonas.TestPersonas
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * De personadienst kent de identiteiten, deze module kent de magazijnen. Wijst een persona naar een
 * magazijn waarvoor hier geen aanlever-URL staat, dan levert de generator niets voor hem aan en
 * blijft zijn berichtenbox leeg — zonder dat er iets faalt. Vandaar een controle bij het opstarten.
 */
class PersonaMagazijnCheckTest {

    private fun persona(id: String = "pietersen", magazijnen: List<String>) = DemoPersona(
        id = id,
        label = "J. Pietersen",
        type = "BSN",
        waarde = "999993653",
        magazijnen = magazijnen,
        bron = PersonaBron.KETEN,
    )

    @Test
    fun `een opt-in op een magazijn zonder aanlever-URL houdt de module tegen`() {
        val fout = assertThrows(IllegalArgumentException::class.java) {
            PersonaMagazijnCheck.vereisBekend(
                listOf(persona(magazijnen = listOf(TestPersonas.RVO, "00000000000000999999"))),
                setOf(TestPersonas.RVO),
            )
        }

        assertTrue(fout.message!!.contains("00000000000000999999"), fout.message)
        assertTrue(fout.message!!.contains("pietersen"), fout.message)

        // Het bekende magazijn hoort er juist niet in te staan: zou het filter omdraaien, dan
        // meldt deze functie alles wat wél klopt en blijft de test zonder deze regel groen.
        assertFalse(fout.message!!.contains(TestPersonas.RVO), fout.message)
    }

    @Test
    fun `zonder ingericht magazijn wijst de melding naar demo-magazijnen en niet naar de persona`() {
        val fout = assertThrows(IllegalArgumentException::class.java) {
            PersonaMagazijnCheck.vereisBekend(listOf(persona(magazijnen = listOf(TestPersonas.RVO))), emptySet())
        }

        assertTrue(fout.message!!.contains("geen magazijn ingericht"), fout.message)
    }

    @Test
    fun `een inrichting waarin alle opt-ins bekend zijn komt er zonder fout doorheen`() {
        // De happy path van deze controle. Zonder deze test bewijst niets dat een correcte
        // inrichting er doorheen komt; alle andere gevallen toetsen juist een weigering.
        PersonaMagazijnCheck.vereisBekend(
            listOf(persona(magazijnen = listOf(TestPersonas.RVO, TestPersonas.BELASTINGDIENST))),
            TestPersonas.MAGAZIJNEN,
        )
    }

    @Test
    fun `een lege personalijst vraagt niets en glijdt er doorheen`() {
        PersonaMagazijnCheck.vereisBekend(emptyList(), TestPersonas.MAGAZIJNEN)
    }

    @Test
    fun `een persona zonder opt-in vraagt geen magazijn en glijdt er doorheen`() {
        // Grootbedrijf staat zo in de echte inrichting: hij haalt op bij de gesimuleerde magazijnen,
        // waar de generator niets voor aanlevert. Dat mag de console niet tegenhouden.
        PersonaMagazijnCheck.vereisBekend(listOf(persona(magazijnen = emptyList())), emptySet())
    }

    @Test
    fun `meldt alle onbekende magazijnen in één keer, over meerdere persona's heen`() {
        // Eén melding per herstart: drie kapotte opt-ins horen geen drie herstarts te kosten.
        val fout = assertThrows(IllegalArgumentException::class.java) {
            PersonaMagazijnCheck.vereisBekend(
                listOf(
                    persona("een", listOf("00000000000000000001")),
                    persona("twee", listOf("00000000000000000002")),
                ),
                setOf(TestPersonas.RVO),
            )
        }

        // Op "persona 'een'" en niet op "een": het woord `geen` in de melding bevat die drie letters,
        // waardoor een assertie op de kale id niet kán falen.
        assertTrue(fout.message!!.contains("persona 'een'"), fout.message)
        assertTrue(fout.message!!.contains("persona 'twee'"), fout.message)
        assertTrue(fout.message!!.contains("00000000000000000001"), fout.message)
        assertTrue(fout.message!!.contains("00000000000000000002"), fout.message)
    }
}
