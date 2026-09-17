package nl.rijksoverheid.moz.fbs.democonsole

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Bewaakt dat een verlopen inlogsessie het paneel niet als kapot laat overkomen.
 *
 * Op een gedeelde omgeving staat dit component achter een oauth2-proxy. Die beantwoordt een
 * aanvraag zonder sessie met HTTP 403 en de inlogpagina als HTML, dus een `fetch()` ziet een
 * mislukking met een body die geen JSON is. Zonder de afhandeling hieronder meldt elke knop iets
 * over een onleesbaar antwoord en blijft elke chip op "onbekend" staan, terwijl er alleen opnieuw
 * ingelogd hoeft te worden — en niets brengt de browser daar uit zichzelf naartoe.
 *
 * Bewust géén `@QuarkusTest`: dit leest de bestanden rechtstreeks van schijf en draait dus zonder
 * Docker. Een browser is er niet en deze build kent geen JS-runner, dus getoetst wordt de vorm van
 * het script. Wat een statische toets niet bereikt — dat de proxy werkelijk 401 geeft en dat de
 * navigatie terugkomt op dezelfde pagina — blijft daarmee ongedekt.
 */
class PaneelInlogmuurTest {

    private val inlogmuur: String = PaneelBestanden.inlogmuur()

    private val script: String = PaneelBestanden.script()

    private val boxScript: String = PaneelBestanden.boxScript()

    @ParameterizedTest(name = "{0} laadt het inlogmuur-script vóór {1}")
    @CsvSource("index.html,bediening.js", "berichtenbox.html,berichtenbox.js")
    fun `elke pagina van dit component laadt het inlogmuur-script eerst`(pagina: String, eigenScript: String) {
        // Beide pagina's komen van dit component en vallen dus achter dezelfde muur; een pagina die
        // het script niet laadt, valt terug op het oude gedrag zonder dat iets dat meldt.
        val bron = if (pagina == "index.html") PaneelBestanden.paneel() else PaneelBestanden.boxPagina()

        val muurTag = bron.indexOf("""src="inlogmuur.js"""")
        val eigenTag = bron.indexOf("""src="$eigenScript"""")

        assertTrue(muurTag >= 0, "$pagina laadt inlogmuur.js niet")
        assertTrue(eigenTag >= 0, "$pagina laadt $eigenScript niet")

        // De volgorde is de invariant: het eigen script roept de muur-functies aan zodra het zijn
        // eerste aanvraag doet, en dat kan al vóór de laatste regel van de pagina gebeuren.
        assertTrue(muurTag < eigenTag, "$pagina laadt inlogmuur.js ná $eigenScript")
    }

    @Test
    fun `de controle gebruikt het enige pad dat de proxy eenduidig beantwoordt`() {
        // /oauth2/auth geeft 202 mét sessie en 401 zonder. Zonder die controle zou een 403 van de
        // applicatie zelf de bediener wegleiden van een pagina die het prima doet, en lokaal — waar
        // geen muur staat — zou hetzelfde gebeuren bij elke 403 uit de demo-API.
        assertTrue(inlogmuur.contains("'/oauth2/auth'"), "het controle-pad van de proxy ontbreekt")
        assertTrue(inlogmuur.contains("status !== 403"), "de controle draait ook bij een andere status dan 403")
        assertTrue(inlogmuur.contains("controle.status === 401"), "een ander antwoord dan 401 telt ook als verlopen")
    }

    @Test
    fun `het herstel stuurt naar de aanmelding van de proxy met een terugkeeradres`() {
        // Rechtstreeks naar /oauth2/start en niet de pagina herladen: herladen levert eerst de
        // inlogkaart met een knop op, terwijl deze route bij een geldige SSO-sessie vanzelf
        // doorloopt. Zonder `rd` komt de bediener terug op een andere pagina dan waar hij stond.
        assertTrue(inlogmuur.contains("'/oauth2/start'"), "het aanmeldpad van de proxy ontbreekt")
        assertTrue(inlogmuur.contains("""'?rd=' + encodeURIComponent("""), "het herstel draagt geen terugkeeradres")
    }

    @Test
    fun `een herstelpoging wordt gedempt, en die demping overleeft de navigatie`() {
        // Een muur die blijft weigeren zou de bediener anders elke poll opnieuw het inlogpad in
        // sturen. De demping hoort in sessionStorage: een teller in het geheugen is ná de navigatie
        // naar de proxy juist weg, precies wanneer de lus zou beginnen.
        assertTrue(inlogmuur.contains("MUUR_DEMPING_MS"), "er is geen dempingstijd")
        assertTrue(inlogmuur.contains("sessionStorage"), "de demping overleeft de navigatie naar de proxy niet")
        assertTrue(
            zonderCommentaar(inlogmuur).contains("if (!dempingVerstreken()) return false"),
            "het herstel raadpleegt de demping niet",
        )
    }

    @Test
    fun `binnen een frame navigeert het herstel niet`() {
        // De proxy en Keycloak laten zich niet insluiten, dus een navigatie in een frame levert de
        // bediener een leeg vak op zonder uitleg.
        assertTrue(
            zonderCommentaar(inlogmuur).contains("if (window.top !== window.self) return false"),
            "het herstel navigeert ook binnen een frame",
        )
    }

    @Test
    fun `elke plek die een mislukte aanvraag toont, raadpleegt eerst de muur`() {
        // Alle drie de plekken waar een 403 zichtbaar wordt: de knoppen, de toestandsbalk en het
        // laden van de omgeving in de berichtenbox. Eén ervan overslaan levert precies de storing
        // op die dit script moet wegnemen — op één plek, wat het lastiger maakt te herkennen.
        val zonderControle = mapOf(
            "roep" to functieBody(script, "roep"),
            "lees" to functieBody(script, "lees"),
            "berichtenbox.js" to boxScript,
        ).filterValues { !it.contains("inlogsessieVerlopen(") }.keys

        assertEquals(emptySet<String>(), zonderControle, "raadpleegt de inlogmuur niet bij een mislukte aanvraag")
    }

    @Test
    fun `de knop controleert de muur vóór hij het antwoord ontleedt`() {
        val roep = functieBody(script, "roep")

        val controle = roep.indexOf("inlogsessieVerlopen(")
        val onleesbaar = roep.indexOf("Onleesbaar antwoord")

        assertTrue(onleesbaar >= 0, "de terugval op een onleesbaar antwoord is weg; deze test meet dan niets")

        // De inlogpagina is HTML, dus het ontleden faalt en de bediener leest "Onleesbaar antwoord
        // (HTTP 403)" — een melding die hem in de keten laat zoeken.
        assertTrue(controle in 0 until onleesbaar, "de muur-controle staat ná de terugval op een onleesbaar antwoord")
    }

    @Test
    fun `de melding over uitloggen staat alleen bij de herstelpoging`() {
        // Eén ingang voor melden en herstellen: een aanroeper die de tekst zelf opschrijft, meldt
        // wel dat je uitgelogd bent maar doet er niets aan — en dat is de toestand van vóór dit
        // script.
        val elders = mapOf(
            "bediening.js" to script,
            "berichtenbox.js" to boxScript,
        ).filterValues { it.contains("uitgelogd") }.keys

        assertEquals(emptySet<String>(), elders, "schrijft de melding over uitloggen zelf op i.p.v. muurMelding()")
        assertTrue(inlogmuur.contains("uitgelogd"), "muurMelding() zegt niet dat je uitgelogd bent")
    }

    /**
     * De body van een functie: vanaf de regel met de declaratie tot de eerste `}` die alleen op een
     * regel staat. Genoeg voor dit script — elke functie hier staat op het hoogste niveau.
     */
    private fun functieBody(bron: String, naam: String): String {
        val begin = Regex("""^(async )?function $naam\(""", RegexOption.MULTILINE).find(bron)

        assertTrue(begin != null, "functie $naam niet gevonden; deze test meet dan niets")

        val rest = bron.substring(begin!!.range.first)
        val einde = rest.indexOf("\n}")

        assertTrue(einde > 0, "einde van functie $naam niet gevonden")

        return rest.substring(0, einde)
    }

    /** Zonder commentaar, zodat een toelichting die een regel citeert niet voor die regel doorgaat. */
    private fun zonderCommentaar(bron: String): String = bron
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(?<!:)//.*"""), "")
}
