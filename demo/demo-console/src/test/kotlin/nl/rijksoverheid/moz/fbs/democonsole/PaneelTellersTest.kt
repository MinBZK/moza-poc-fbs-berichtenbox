package nl.rijksoverheid.moz.fbs.democonsole

import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

/**
 * Bewaakt dat het paneel élke teller van een vulronde leest en meeweegt.
 *
 * De twee kanten hangen aan losse stringliterals: Kotlin serialiseert de veldnamen, `bediening.js`
 * leest ze op naam. Een teller die er in Kotlin bij komt maar in het script ontbreekt is in
 * JavaScript stil `undefined` — `vullingTekst` laat de zin dan weg en `vullingSoort` valt terug op
 * "goed", dus het paneel meldt groen "100 van 100 aangeleverd" voor een ronde die haperde. Precies
 * het beeld dat een vulronde nooit meer mag geven.
 *
 * Bewust géén `@QuarkusTest`: dit leest het script van de classpath en draait dus zonder Docker.
 * `PaneelContractTest` bewaakt de andere kant — dat het antwoord precies deze velden draagt.
 */
class PaneelTellersTest {

    private val script: String = javaClass.getResource(SCRIPT)!!.readText()

    private val tellers: List<String> = AanleverResultaat::class.memberProperties.map { it.name }

    /** De tellers die iets zeggen over wat er misging; `aangeboden` en `geslaagd` doen dat niet. */
    private val fouttellers: List<String> = tellers - setOf("aangeboden", "geslaagd")

    @Test
    fun `het script is te vinden en draagt de samenvatters`() {
        // Zonder deze twee zouden de tests hieronder falen met een melding over een teller, terwijl
        // het probleem is dat het bestand of de functie er niet meer is.
        assertTrue(script.isNotBlank(), "bediening.js is leeg of niet gevonden")
        assertTrue("function vullingSoort(" in script, "vullingSoort bestaat niet meer")
    }

    @Test
    fun `het paneel leest elke teller van de uitkomst`() {
        // Komt hier een teller bij, dan moet bediening.js mee — deze test is de plek waar dat blijkt.
        val ongelezen = tellers.filterNot { "vulling.$it" in script }

        assertTrue(ongelezen.isEmpty(), "bediening.js leest deze tellers niet: $ongelezen")
    }

    @Test
    fun `elke foutteller weegt mee in de kleur van de melding`() {
        // vullingTekst noemt een fouttelling; vullingSoort bepaalt of de melding groen of oranje is.
        // Staat een teller alleen in de tekst, dan leest een haperende ronde alsnog als geslaagd.
        val soort = script.substringAfter("function vullingSoort(").substringBefore("\n}")
        val ongewogen = fouttellers.filterNot { "vulling.$it" in soort }

        assertTrue(ongewogen.isEmpty(), "vullingSoort weegt deze tellers niet mee: $ongewogen")
    }

    @Test
    fun `elke uitkomstsoort krijgt een eigen merkteken`() {
        // Ontbreekt een soort in MERKTEKEN, dan valt hij terug op de standaardwaarde en krijgt een
        // uitkomst het merkteken van een andere. Dat was hoe een volledig mislukte vulling een groen
        // vinkje kreeg naast een rode melding.
        val soort = script.substringAfter("function vullingSoort(").substringBefore("\n}")
        val tabel = script.substringAfter("const MERKTEKEN = {").substringBefore("}")
        val teruggegeven = Regex("return ([^;]*);").findAll(soort).map { it.groupValues[1] }
        val soorten = teruggegeven.flatMap { Regex("'([a-z-]+)'").findAll(it) }
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(soorten.isNotEmpty(), "geen uitkomstsoorten gevonden in vullingSoort")
        assertEquals(
            emptySet<String>(),
            soorten.filterNot { "'$it'" in tabel }.toSet(),
            "MERKTEKEN kent deze uitkomstsoorten niet",
        )
    }

    private companion object {

        const val SCRIPT = "/META-INF/resources/bediening.js"
    }
}
