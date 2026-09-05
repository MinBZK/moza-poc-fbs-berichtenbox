package nl.rijksoverheid.moz.fbs.democonsole

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * Bewaakt welke adressen de knoppen van het paneel aanroepen.
 *
 * Een knop die naar een adres wijst dat deze module niet beantwoordt, faalt met een 404 die eruit
 * ziet als een kapotte keten — en dat merk je pas tijdens een demo, want lokaal zet de demo-proxy
 * hetzelfde pad wél door naar een andere dienst. Rechtstreeks op poort 8095 en op een gedeelde
 * omgeving is er geen proxy.
 *
 * Bewust géén `@QuarkusTest`: dit leest het bestand rechtstreeks van schijf en draait dus zonder
 * Docker.
 */
class PaneelPadenTest {

    private val paneel: String = File(PANEEL).readText()

    private val script: String = File(SCRIPT).readText()

    /**
     * Het script zonder commentaar. De tellingen hieronder zoeken naar code-constructies, en een
     * comment die een functie noemt — precies wat dit project aanmoedigt — zou anders als een extra
     * aanroep tellen. De test faalt dan met de omgekeerde diagnose van wat er aan de hand is.
     *
     * De `(?<!:)` houdt een URL heel: zonder die uitzondering snijdt `https://…` de rest van zijn
     * regel weg, en dan telt een echte aanroep juist te wéinig — dezelfde verkeerde diagnose.
     */
    private val code: String = script
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(?<!:)//.*"""), "")

    private val paden: List<String> = uitPaneel("""data-pad="([^"]+)"""")

    @Test
    fun `het paneel draagt knoppen met een adres`() {
        // Zonder deze assertie zouden de tests hieronder over een lege lijst lopen en altijd slagen,
        // ook als de regex nooit meer iets vindt.
        assertTrue(paden.isNotEmpty(), "geen enkele data-pad gevonden in index.html")
    }

    @Test
    fun `elke knop wijst naar de eigen demo-API`() {
        // Het paneel praat uitsluitend met de console zelf; alles daarbuiten (de uitvraag, de
        // berichtenbox) loopt via de pagina's, niet via deze knoppen.
        val vreemd = paden.filterNot { it.startsWith("/api/demo/") }

        assertEquals(emptyList<String>(), vreemd, "knoppen horen de eigen /api/demo-API aan te roepen")
    }

    @Test
    fun `geen knop roept het adres van de personadienst aan`() {
        // `personadienst.endpoint=false` schakelt dat adres hier juist uit — PaneelContractTest pint
        // vast dat het 404 hoort te geven. De lijst komt uit /api/demo/omgeving, uit dezelfde bron.
        assertTrue(
            "/api/demo/personas" !in paden,
            "het paneel hoort de personalijst uit /api/demo/omgeving te lezen",
        )
    }

    /**
     * De storingsknoppen hangen aan `data-proxy`. Een naam die niet in de configuratie staat, faalt
     * niet zichtbaar maar *stil*: `pasOmgevingToe` zet `knop.hidden` op alles wat niet in
     * `/api/demo/omgeving` voorkomt, dus de knop verdwijnt gewoon — geen 400, geen log, geen rode
     * chip. Wie het runbook volgt concludeert dan dat deze omgeving dat scenario niet ondersteunt.
     */
    @Test
    fun `elke storingsknop noemt een geconfigureerde proxy`() {
        val uitPaneel = uitPaneel("""data-proxy="([^"]+)"""").toSet()
        val uitConfiguratie = Properties()
            .apply { File(PROPERTIES).inputStream().use { load(it) } }
            .stringPropertyNames()
            .mapNotNull { Regex("""demo\.toxiproxy\."([^"]+)"\.url""").matchEntire(it)?.groupValues?.get(1) }
            .toSet()

        assertTrue(uitPaneel.isNotEmpty(), "geen enkele data-proxy gevonden in $PANEEL")
        assertTrue(uitConfiguratie.isNotEmpty(), "geen enkele demo.toxiproxy-url gevonden in $PROPERTIES")
        assertEquals(emptySet<String>(), uitPaneel - uitConfiguratie, "knoppen voor een onbekende proxy")
    }

    /**
     * Elk `{veld}` in een knop-adres moet een element in dezelfde pagina aanwijzen, én in `VELDEN`
     * staan.
     *
     * Beide falen stil. Wijst het niet naar een element, dan geeft `vulPadIn` null terug en keert
     * `voerUit` terug zonder melding of merkteken: de knop doet niets en niets zegt waarom. Staat
     * het veld niet in `VELDEN`, dan bewaart het paneel de invoer niet en herstelt hij hem ook niet
     * — een keuzelijst valt na een refresh stil terug op zijn eerste optie.
     */
    @Test
    fun `elk veld in een knop-adres bestaat en wordt bewaard`() {
        val velden = uitPaneel("""\{(\w+)}""").toSet()
        val elementen = uitPaneel("""id="([^"]+)"""").toSet()
        val bewaard = Regex("""const VELDEN = \[([^\]]+)]""")
            .find(script)
            ?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().trim('\'') }
            ?.toSet()
            .orEmpty()

        assertTrue(velden.isNotEmpty(), "geen enkel {veld} gevonden in $PANEEL")
        assertTrue(bewaard.isNotEmpty(), "de VELDEN-lijst niet gevonden in $SCRIPT")
        assertEquals(emptySet<String>(), velden - elementen, "knop-adres verwijst naar een onbekend veld")
        assertEquals(emptySet<String>(), velden - bewaard, "veld uit een knop-adres ontbreekt in VELDEN")
    }

    /**
     * Een `data-samenvatting` die `bediening.js` niet kent, valt terug op een kaal groen "Gelukt":
     * de knop meldt succes zonder de samenvatting die zegt wát er gebeurde.
     *
     * Eerst het blok afbakenen en dan pas de sleutels lezen: zoeken over het hele script accepteert
     * ook de sleutels van de buur-objecten, en juist daar komt de verwisseling vandaan — een
     * `data-actie`-naam als `ververs-box` of een proxy-naam als `magazijn-a` in dit attribuut.
     */
    @Test
    fun `elke knop noemt een samenvatting die het script kent`() {
        val sleutels = sleutelsVan("SAMENVATTINGEN")
        val uitPaneel = uitPaneel("""data-samenvatting="([^"]+)"""").toSet()

        assertTrue(uitPaneel.isNotEmpty(), "geen enkele data-samenvatting gevonden in $PANEEL")
        assertTrue(sleutels.isNotEmpty(), "het SAMENVATTINGEN-blok niet gevonden in $SCRIPT")

        // Eerst bewijzen dat de meting discrimineert: deze naam staat wél in het script, in een
        // ander object. Accepteert de test hem, dan bewaakt hij niets.
        assertTrue("ververs-box" !in sleutels, "de sleutels komen niet uit SAMENVATTINGEN alleen")

        assertEquals(
            emptySet<String>(),
            uitPaneel - sleutels,
            "knoppen met een samenvatting die niet in SAMENVATTINGEN staat",
        )
    }

    /** De sleutels van één object-literal op het hoogste niveau van `bediening.js`. */
    private fun sleutelsVan(objectnaam: String): Set<String> {
        val blok = Regex("""^const $objectnaam = \{$(.*?)^};$""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(script)
            ?.groupValues?.get(1)
            .orEmpty()

        return Regex("""^ {4}'?([\w-]+)'?:""", RegexOption.MULTILINE).findAll(blok).map { it.groupValues[1] }.toSet()
    }

    /**
     * De bovengrens staat zowel in het invoerveld als in de resource. Lopen ze uiteen, dan weigert
     * de server een waarde die de browser aanbiedt — of andersom, en dan is de grens er niet.
     */
    @Test
    fun `het aantal-veld voor een gericht bericht deelt zijn grenzen met de resource`() {
        val veld = Regex("""<input id="berichtAantal"[^>]*>""").find(paneel)?.value

        assertTrue(veld != null, "het veld berichtAantal niet gevonden in $PANEEL")
        assertEquals("""min="1"""", Regex("""min="\d+"""").find(veld!!)?.value)
        assertEquals("""max="${DemoResource.MAX_BERICHTEN}"""", Regex("""max="\d+"""").find(veld)?.value)
    }

    /**
     * Een identificatienummer hoort niet in een webadres — niet in de productcode en ook niet in
     * de demo. Een adres belandt in browsergeschiedenis, in proxylogboeken en in de schermopname
     * van een demonstratie. De nummers van de demo zijn fictief, maar een knop die het voordoet is
     * het verkeerde voorbeeld; de personadienst weigert om dezelfde reden al een persona-id die
     * een nummer is.
     *
     * Een allowlist en geen lijst van verboden namen: `?bsn=` afvangen laat `?nummer=` en
     * `?ontvangerWaarde=` erdoor, terwijl dat dezelfde knop is. Zo'n adres draagt het nummer pas
     * op het moment dat het paneel het veld invult, dus in het bestand is er niets aan te zien.
     * Bijschrijven hier hoort daarom een bewuste stap te zijn.
     */
    @Test
    fun `elke knop-parameter staat op de lijst van toegestane namen`() {
        // `&amp;` terug naar `&`: in HTML staat de scheiding tussen twee parameters als entiteit,
        // en zonder deze stap heet de tweede parameter "amp;aantal".
        val parameters = paden
            .flatMap { pad -> PARAMETERNAAM.findAll(pad.replace("&amp;", "&")).map { it.groupValues[1] } }
            .toSet()

        assertTrue(parameters.isNotEmpty(), "geen enkele queryparameter gevonden in $PANEEL")

        // Eerst bewijzen dat de meting discrimineert: de vorm die deze test moet vangen.
        assertEquals(
            listOf("bsn"),
            PARAMETERNAAM.findAll("/api/demo/ontdubbeling?bsn={ontdubbelPersona}").map { it.groupValues[1].trim() }.toList(),
        )

        assertEquals(
            emptySet<String>(),
            parameters - TOEGESTANE_PARAMETERS,
            "knop-adres met een parameter buiten de toegestane namen; wijst hij een persona aan, " +
                "gebruik dan zijn id en niet zijn identificatienummer",
        )
    }

    /**
     * Het nummer komt er net zo goed in zonder dat een adres het toont: een keuzelijst die het als
     * optie-waarde zet, vult `{ontdubbelPersona}` ermee. Vandaar over de héle inhoud.
     *
     * De directory uitlezen en geen vaste lijst: een pagina die er later bijkomt — ook in een
     * submap — hoort er vanzelf onder te vallen, anders bewaakt deze test precies de bestanden
     * waarin het probleem al opgelost is.
     */
    @Test
    fun `geen pagina van het paneel draagt een identificatienummer`() {
        // Acht of negen cijfers is de vorm van een KVK-nummer, BSN of RSIN; een langere reeks blijft
        // toegestaan, want een OIN is publiek.
        val nummer = Regex("""(?<!\d)\d{8,9}(?!\d)""")
        val paginas = File(RESOURCES).walkTopDown().filter { it.extension in PAGINA_TYPES }.toList()

        assertTrue(
            paginas.any { it.name == "index.html" },
            "index.html niet gevonden onder $RESOURCES; dan meet deze test de verkeerde bestanden",
        )

        // Eerst bewijzen dat de meting onderscheidt: de vorm die deze test moet vangen én de vorm
        // die mag blijven. Zonder deze regels zou een regex die alles of niets matcht even groen zijn.
        assertTrue(nummer.containsMatchIn("/api/demo/ontdubbeling?persona=123456789"))
        assertFalse(nummer.containsMatchIn("/magazijn/00000000000000100000"), "een OIN is publiek")

        paginas.forEach { bestand ->
            assertEquals(
                emptyList<String>(),
                nummer.findAll(bestand.readText()).map { it.value }.toList(),
                "${bestand.name} draagt een reeks van acht of negen cijfers; is het geen " +
                    "identificatienummer, zet die constante dan buiten deze bestanden",
            )
        }
    }

    /**
     * De keuzelijsten leveren de waarde die in het adres terechtkomt. Zet een lijst daar het
     * identificatienummer neer in plaats van de persona-id, dan draagt het adres het nummer alsnog
     * — en `index.html` toont dat niet, want daar staat alleen `{ontdubbelPersona}`.
     *
     * De harde grens ligt in `vulKeuze`, dat een optie-waarde met een cijferreeks weigert; dat werkt
     * ook voor een `{veld}` in een padsegment, waar geen queryparameter aan te pas komt. Deze
     * controle bewaakt de weg ernaartoe: een lijst die zijn opties zelf opbouwt, komt daar niet
     * langs.
     */
    @Test
    fun `elke keuzelijst levert de persona-id als optie-waarde`() {
        val waarden = OPTIEWAARDE.findAll(code).map { it.groupValues[1].trim() }.toList()

        assertTrue(waarden.isNotEmpty(), "geen enkele optie-waarde gevonden in $SCRIPT")

        // Eerst bewijzen dat de meting discrimineert: dit is de vorm die het nummer wél doorgaf,
        // inclusief de schrijfwijze met de waarde als láátste sleutel.
        assertEquals(
            listOf("persona.ontvanger.slice('BSN:'.length)", "persona.ontvanger"),
            OPTIEWAARDE.findAll(
                "waarde: persona.ontvanger.slice('BSN:'.length), label: persona.label }\n" +
                    "{ label: persona.label, waarde: persona.ontvanger }",
            ).map { it.groupValues[1].trim() }.toList(),
        )

        assertEquals(
            emptyList<String>(),
            waarden.filterNot { it == "persona.id" },
            "optie-waarde die niet de persona-id is; het nummer hoort niet in het adres terecht te komen",
        )
    }

    /**
     * Elke keuzelijst loopt door dezelfde twee hulpfuncties: `heeftAlleVelden` weigert een lijst
     * waarin een persona zijn id mist, `vulKeuze` bouwt de opties en weigert een cijferreeks als
     * waarde. Bouwt een lijst zijn opties zelf op, dan valt hij buiten allebei — en de controle
     * hierboven ziet dat niet, want er is dan geen `waarde:` meer te vinden.
     */
    @Test
    fun `elke keuzelijst loopt door dezelfde hulpfuncties`() {
        // Geteld in de pagina en niet in het script: hier staat hoeveel keuzelijsten er zijn, dus
        // een lijst die zijn opties zelf opbouwt valt op als een ontbrekende aanroep in plaats van
        // als een getal dat niemand meer kan narekenen.
        val keuzelijsten = uitPaneel("""<select id="([^"]+)"""").size

        assertTrue(keuzelijsten > 1, "minder dan twee keuzelijsten in $PANEEL; dan toetst dit niets")
        assertEquals(keuzelijsten, aanroepen("vulKeuze"), "een keuzelijst bouwt zijn opties buiten vulKeuze om")
        assertEquals(keuzelijsten, OPTIEWAARDE.findAll(code).count(), "een optie-waarde buiten een keuzelijst om")
        assertEquals(
            keuzelijsten,
            aanroepen("heeftAlleVelden"),
            "een keuzelijst zonder de controle op ontbrekende id's",
        )
    }

    /**
     * Het paneel filtert de ontdubbel-keuzelijst op het type dat de resource als enige accepteert.
     * Drijft dat filter af — naar `KVK:` bijvoorbeeld — dan biedt de lijst uitsluitend persona's
     * aan die elke klik met een 400 beantwoorden, en niets in de keten merkt dat: de contracttest
     * bouwt dezelfde regel na in Kotlin en blijft dus groen.
     */
    @Test
    fun `de keuzelijst filtert op hetzelfde type dat de ontdubbeling accepteert`() {
        assertTrue(
            code.contains("""startsWith('BSN:')"""),
            "$SCRIPT filtert niet meer op BSN; de ontdubbeling accepteert geen ander type",
        )
    }

    /** Aanroepen van [functie] in het script; de definitie zelf telt niet mee. */
    private fun aanroepen(functie: String): Int =
        Regex("""(?<!function )\b$functie\(""").findAll(code).count()

    private fun uitPaneel(patroon: String): List<String> =
        Regex(patroon).findAll(paneel).map { it.groupValues[1] }.toList()

    private companion object {

        const val RESOURCES = "src/main/resources/META-INF/resources"

        const val PANEEL = "$RESOURCES/index.html"

        const val SCRIPT = "$RESOURCES/bediening.js"

        const val PROPERTIES = "src/main/resources/application.properties"

        /** Waarin een adres of een optie-waarde kan staan; de opmaak van de `.css` erbuiten. */
        val PAGINA_TYPES = setOf("html", "js")

        /**
         * Wat een knop-adres als queryparameter mag dragen. Een naam die een persona of een andere
         * partij aanwijst hoort een id te zijn; wie hier een naam bijschrijft, kiest daar bewust
         * voor.
         */
        val TOEGESTANE_PARAMETERS = setOf("persona", "aantal", "interval")

        val PARAMETERNAAM = Regex("""[?&]([^=&]+)=""")

        /**
         * Elke `waarde:` in het script; die gaat via `vulKeuze` naar een `<option>` en komt zo in
         * het adres van een knop terecht. `[^,\n}]` en niet `[^,]`: zonder de sluitaccolade ontgaat
         * hem de schrijfwijze met de waarde als laatste sleutel, en zonder de newline loopt een
         * capture door tot de komma van de omliggende argumentenlijst.
         */
        val OPTIEWAARDE = Regex("""waarde: ([^,\n}]+)""")
    }
}
