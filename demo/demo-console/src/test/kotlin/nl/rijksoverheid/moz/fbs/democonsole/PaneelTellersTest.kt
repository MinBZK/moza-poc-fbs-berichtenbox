package nl.rijksoverheid.moz.fbs.democonsole

import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.reflect.full.memberProperties

/**
 * Bewaakt dat het paneel élke teller van een vulronde leest.
 *
 * De twee kanten hangen aan losse stringliterals: Kotlin serialiseert de veldnamen, `bediening.js`
 * leest ze op naam. Een teller die er in Kotlin bij komt maar in het script ontbreekt is in
 * JavaScript stil `undefined` — `vullingTekst` laat de zin dan weg en `vullingSoort` valt terug op
 * "goed", dus het paneel meldt groen "100 van 100 aangeleverd" voor een ronde die haperde. Precies
 * het beeld dat een vulronde nooit meer mag geven.
 *
 * Bewust géén `@QuarkusTest`: dit leest het script rechtstreeks van schijf en draait dus zonder
 * Docker. `PaneelContractTest` bewaakt de andere kant — dat het antwoord precies deze velden draagt.
 */
class PaneelTellersTest {

    private val script: String = File(SCRIPT).readText()

    private val tellers: List<String> = AanleverResultaat::class.memberProperties.map { it.name }

    @Test
    fun `de uitkomst draagt tellers om te lezen`() {
        // Zonder deze assertie loopt de test hieronder over een lege lijst en slaagt hij altijd.
        assertEquals(5, tellers.size, "onverwacht aantal tellers: $tellers")
    }

    @Test
    fun `het paneel leest elke teller van de uitkomst`() {
        val ongelezen = tellers.filterNot { "vulling.$it" in script }

        assertTrue(ongelezen.isEmpty(), "bediening.js leest deze tellers niet: $ongelezen")
    }

    @Test
    fun `een teller die iets meldt weegt ook mee in de kleur van de melding`() {
        // vullingTekst noemt een fouttelling; vullingSoort bepaalt of de melding groen of oranje is.
        // Staat een teller alleen in de tekst, dan leest een haperende ronde alsnog als geslaagd.
        val soort = script.substringAfter("function vullingSoort(").substringBefore("\n}")

        listOf("mislukt", "markeringMislukt", "zonderBerichtId").forEach { teller ->
            assertTrue("vulling.$teller" in soort, "vullingSoort weegt $teller niet mee")
        }
    }

    private companion object {

        const val SCRIPT = "src/main/resources/META-INF/resources/bediening.js"
    }
}
