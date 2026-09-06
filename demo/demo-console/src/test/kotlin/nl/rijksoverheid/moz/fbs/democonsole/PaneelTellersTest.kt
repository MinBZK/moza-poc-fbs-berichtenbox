package nl.rijksoverheid.moz.fbs.democonsole

import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

/**
 * Klemt de Kotlin-kant en het paneel aan elkaar vast: leest `bediening.js` élke teller van een
 * vulronde, en wijst elke uitkomstsoort naar een eigen merkteken?
 *
 * De twee kanten hangen aan losse stringliterals: Kotlin serialiseert de veldnamen, het script leest
 * ze op naam. Een teller die er in Kotlin bij komt maar in het script ontbreekt is in JavaScript stil
 * `undefined` — `vullingTekst` laat de zin dan weg en `vullingSoort` valt terug op "goed", dus het
 * paneel meldt groen "100 van 100 aangeleverd" voor een ronde die haperde. Precies het beeld dat een
 * vulronde nooit meer mag geven.
 *
 * Dit is een naamkoppeling, geen gedragstest: er is geen JS-runtime in de build, dus wát
 * `vullingTekst` van een uitkomst maakt blijft ongetoetst. Elke controle hieronder faalt daarom
 * luidruchtig op een ontbrekend anker, zodat een hernoemde functie niet als een ontbrekende teller
 * leest.
 *
 * Bewust géén `@QuarkusTest`: dit leest het script van de classpath en draait dus zonder Docker.
 * `PaneelContractTest` bewaakt de andere kant — dat het antwoord precies deze velden draagt.
 */
class PaneelTellersTest {

    private val script: String = javaClass.getResource(SCRIPT)!!.readText()

    /** Zonder commentaar: een teller die alleen in een comment staat, wordt niet gelezen. */
    private val code: String = script
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .map { it.substringBefore("//") }
        .joinToString("\n")

    private val tellers: List<String> = AanleverResultaat::class.memberProperties.map { it.name }

    /** De tellers die iets zeggen over wat er misging; `aangeboden` en `geslaagd` doen dat niet. */
    private val fouttellers: List<String> = tellers - setOf("aangeboden", "geslaagd")

    /** De uitkomstsoorten die `vullingSoort` kan teruggeven. */
    private val soorten: Set<String> = Regex("return ([^;]*);").findAll(deel("function vullingSoort("))
        .flatMap { Regex("'([A-Za-z0-9-]+)'").findAll(it.groupValues[1]) }
        .map { it.groupValues[1] }
        .toSet()

    /** `MERKTEKEN`, uitgelezen als uitkomstsoort → merkteken. */
    private val merktekens: Map<String, String> = Regex("'([A-Za-z0-9-]+)'\\s*:\\s*'([A-Za-z0-9-]+)'")
        .findAll(deel("const MERKTEKEN = {", "}"))
        .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `het script draagt de samenvatters die dit bestand uitleest`() {
        // Zonder deze controle zouden de tests hieronder falen met een melding over een teller of een
        // soort, terwijl het probleem is dat de functie of de tabel er niet meer is.
        assertTrue(soorten.isNotEmpty(), "geen uitkomstsoorten gevonden in vullingSoort")
        assertTrue(merktekens.isNotEmpty(), "MERKTEKEN niet gevonden of leeg")
    }

    @Test
    fun `de samenvattende tekst noemt elke teller`() {
        // Komt hier een teller bij, dan moet bediening.js mee — deze test is de plek waar dat blijkt.
        // Per functie en niet over het hele bestand: anders volstaat het dat vullingSoort hem noemt.
        val tekst = deel("function vullingTekst(")
        val ongenoemd = tellers.filterNot { "vulling.$it" in tekst }

        assertTrue(ongenoemd.isEmpty(), "vullingTekst noemt deze tellers niet: $ongenoemd")
    }

    @Test
    fun `elke foutteller weegt mee in de kleur van de melding`() {
        // vullingTekst noemt een fouttelling; vullingSoort bepaalt of de melding groen of oranje is.
        // Staat een teller alleen in de tekst, dan leest een haperende ronde alsnog als geslaagd.
        val soort = deel("function vullingSoort(")
        val ongewogen = fouttellers.filterNot { "vulling.$it" in soort }

        assertTrue(ongewogen.isEmpty(), "vullingSoort weegt deze tellers niet mee: $ongewogen")
    }

    @Test
    fun `een ronde waarin niets aankwam kan als fout worden gemeld`() {
        // Zonder deze uitkomst bestaat er geen enkele weg naar een rode melding en leest "0 van 100
        // aangeleverd" als een ronde met een kanttekening.
        assertTrue("fout" in soorten, "vullingSoort kan geen fout meer teruggeven: $soorten")
    }

    @Test
    fun `elke uitkomstsoort krijgt een eigen merkteken`() {
        // Wijzen twee soorten naar hetzelfde teken, dan draagt de ene het signaal van de andere —
        // en zo kreeg een volledig mislukte vulling een groen vinkje naast een rode melding.
        assertEquals(
            emptySet<String>(),
            soorten - merktekens.keys,
            "MERKTEKEN kent deze uitkomstsoorten niet",
        )
        // Niet alleen "elk teken komt één keer voor": twee soorten van teken laten wisselen zou dat
        // overleven, en dan draagt een mislukte ronde het teken van een geslaagde.
        assertEquals(
            mapOf("goed" to "gelukt", "let-op" to "let-op", "fout" to "mislukt"),
            merktekens,
            "een uitkomstsoort wijst naar het verkeerde merkteken",
        )
    }

    @Test
    fun `een onbekende uitkomstsoort valt niet terug op het geslaagd-teken`() {
        // De terugval is er voor een soort die later wordt toegevoegd. Wijst hij naar 'gelukt', dan
        // is dat opnieuw een groen vinkje voor iets wat we niet begrijpen.
        val terugval = Regex("MERKTEKEN\\[[^\\]]+\\]\\s*\\|\\|\\s*'([A-Za-z0-9-]+)'").find(code)

        // Ook de opzoeksleutel: een tabel die op iets anders dan de uitkomstsoort wordt bevraagd,
        // geeft élke knop hetzelfde teken — en dan is het gepinde merkteken niets waard.
        assertTrue(
            "MERKTEKEN[samengevat.soort]" in code,
            "het merkteken komt niet uit de soort van de samenvatting",
        )
        assertTrue(terugval != null, "geen terugval gevonden achter MERKTEKEN[...]")
        assertNotEquals(merktekens["goed"], terugval!!.groupValues[1], "een onbekende soort leest als geslaagd")
    }

    /**
     * Het stuk script tussen twee ankers, of een lege string als het eerste anker ontbreekt.
     *
     * De ankers en de regexen hierboven lezen enkele quotes en de huidige schrijfwijze van de tabel;
     * een herformattering naar dubbele quotes of kale sleutels laat deze tests falen op een melding
     * over een ontbrekende soort. Dat is de veilige kant om op te falen, maar het is geen fout in
     * het paneel — pas dan de patronen aan.
     */
    private fun deel(vanaf: String, tot: String = "\n}"): String =
        if (vanaf in code) code.substringAfter(vanaf).substringBefore(tot) else ""

    private companion object {

        const val SCRIPT = "/META-INF/resources/bediening.js"
    }
}
