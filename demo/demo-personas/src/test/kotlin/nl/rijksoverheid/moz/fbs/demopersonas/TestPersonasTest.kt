package nl.rijksoverheid.moz.fbs.demopersonas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * [TestPersonas] gaat als test-jar mee naar de demo-console, die er zijn eigen dataset tegen
 * toetst. De guards van die parser bewaken dat hij hetzelfde leest als SmallRye; draai je er één om
 * dan is het effect stil — de parser leest dan een ander bestand of een halve lijst, en de
 * kruiscontrole in de console vergelijkt vanaf dat moment appels met appels uit dezelfde mand.
 */
class TestPersonasTest {

    @Test
    fun `meldt een profiel-sleutel in plaats van hem stil te negeren`() {
        val fout = assertThrows(IllegalStateException::class.java) {
            TestPersonas.vereisGeenProfielSleutels(
                eigenschappen("%dev.demo.personas.pietersen.label" to "J. Pietersen"),
            )
        }

        assertTrue(fout.message!!.contains("%dev.demo.personas.pietersen.label"), fout.message)
    }

    @Test
    fun `laat een gewone sleutel en een profiel-sleutel buiten demo met rust`() {
        // Alleen profiel-sleutels die de persona's raken zijn een probleem; de dienst zet er zelf
        // geen, maar een afnemer mag bijvoorbeeld `%dev.quarkus.http.port` gewoon overschrijven.
        TestPersonas.vereisGeenProfielSleutels(
            eigenschappen(
                "demo.personas.pietersen.label" to "J. Pietersen",
                "%dev.quarkus.http.port" to "8098",
            ),
        )
    }

    @Test
    fun `meldt het wanneer er geen enkele persona-sleutel te vinden is`() {
        val fout = assertThrows(IllegalStateException::class.java) {
            TestPersonas.personaVelden(eigenschappen("quarkus.http.port" to "8098"))
        }

        assertTrue(fout.message!!.contains("SLEUTEL"), fout.message)
    }

    @Test
    fun `meldt een expressie die deze parser niet expandeert`() {
        val fout = assertThrows(IllegalStateException::class.java) {
            TestPersonas.personaVelden(eigenschappen("demo.personas.pietersen.label" to "\${ergens.anders}"))
        }

        assertTrue(fout.message!!.contains("geëxpandeerd"), fout.message)
    }

    @Test
    fun `groepeert de velden per persona-id en laat de rest liggen`() {
        // Meerdere persona's én een sleutel die er niet bij hoort: met één persona blijft
        // ongetoetst of de parser per id groepeert of alles op één hoop gooit.
        val velden = TestPersonas.personaVelden(
            eigenschappen(
                "demo.personas.pietersen.label" to "J. Pietersen",
                "demo.personas.pietersen.type" to "BSN",
                "demo.personas.bakkerij.label" to "Bakkerij De Vroege Vogel",
                "quarkus.http.port" to "8098",
            ),
        )

        assertEquals(setOf("pietersen", "bakkerij"), velden.keys)
        assertEquals(mapOf("label" to "J. Pietersen", "type" to "BSN"), velden["pietersen"])
        assertEquals(mapOf("label" to "Bakkerij De Vroege Vogel"), velden["bakkerij"])
    }

    private fun eigenschappen(vararg paren: Pair<String, String>) = Properties().apply {
        paren.forEach { (sleutel, waarde) -> setProperty(sleutel, waarde) }
    }
}
