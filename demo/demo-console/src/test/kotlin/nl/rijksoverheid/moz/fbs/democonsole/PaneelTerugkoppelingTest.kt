package nl.rijksoverheid.moz.fbs.democonsole

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bewaakt dat een druk op een knop in het paneel altijd een zichtbaar antwoord oplevert.
 *
 * Een knop die niets doet én niets meldt is tijdens een demonstratie de lastigste storing: er is
 * niets om aan te trekken, dus de bediener drukt nog eens. De invarianten hieronder staan deels in
 * de opmaak en deels in het script, en ze zijn alleen samen sluitend — daarom staan ze in één
 * klasse.
 *
 * Bewust géén `@QuarkusTest`: dit leest de bestanden rechtstreeks van schijf en draait dus zonder
 * Docker. Een browser is er niet, dus getoetst wordt wat het gedrag draagt: de attributen in de
 * opmaak en de vorm van het script.
 */
class PaneelTerugkoppelingTest {

    private val paneel: String = PaneelBestanden.paneel()

    private val script: String = PaneelBestanden.script()

    /** De id's die een knop-adres invult, bijvoorbeeld `berichtAantal` uit `?aantal={berichtAantal}`. */
    private val veldIds: Set<String> = Regex("""data-pad="([^"]+)"""")
        .findAll(paneel)
        .flatMap { Regex("""\{(\w+)}""").findAll(it.groupValues[1]) }
        .map { it.groupValues[1] }
        .toSet()

    @Test
    fun `de opmaak draagt knoppen die een veld invullen`() {
        // Zonder deze assertie lopen de tests hieronder over een lege verzameling en slagen ze
        // altijd, ook als de attributen verdwijnen waar ze naar kijken.
        assertTrue(veldIds.isNotEmpty(), "geen enkel {veld} gevonden in een knop-adres")
    }

    /**
     * Een leeg veld levert een melding op die zegt wélk veld ontbreekt, en die naam komt uit
     * `data-veldnaam`. Ontbreekt dat attribuut, dan valt de melding terug op de id — begrijpelijk
     * voor wie het script kent, niet voor wie een demo geeft.
     */
    @Test
    fun `elk veld achter een knop draagt een naam voor de melding`() {
        val zonderNaam = veldIds.filterNot { id -> Regex("""data-veldnaam="[^"]+"""").containsMatchIn(element(id)) }

        assertEquals(emptyList<String>(), zonderNaam, "veld achter een knop zonder data-veldnaam")
    }

    /**
     * `required` laat de browser het veld zelf aanwijzen naast de melding van het paneel. Zonder
     * dat attribuut is een leeggemaakt veld gewoon geldig, en zwijgt `reportValidity()`.
     */
    @Test
    fun `elk getalveld achter een knop is verplicht`() {
        val getalvelden = veldIds.map { element(it) }.filter { "type=\"number\"" in it }

        assertTrue(getalvelden.isNotEmpty(), "geen enkel getalveld achter een knop gevonden")

        val vrijblijvend = getalvelden.filterNot { "required" in it }

        assertEquals(emptyList<String>(), vrijblijvend, "getalveld achter een knop zonder required")
    }

    /**
     * De keuzelijsten worden pas na een netwerkaanroep gevuld. Tot dat moment kan de knop ernaast
     * niets versturen, en dus hoort hij er ook niet uit te zien alsof hij dat kan — in de opmaak al,
     * want juist de aanroep die nooit terugkomt is het geval dat dit moet dekken.
     */
    @Test
    fun `een knop die op een keuzelijst wacht staat in de opmaak uit`() {
        val keuzelijsten = veldIds.filter { element(it).startsWith("<select") }

        assertTrue(keuzelijsten.isNotEmpty(), "geen enkele keuzelijst achter een knop gevonden")

        keuzelijsten.forEach { id ->
            val lijst = element(id)

            assertTrue("disabled" in lijst, "keuzelijst $id staat bij het laden al aan")
            assertTrue("value=\"\"" in lijst, "keuzelijst $id draagt geen optie met een lege waarde")

            val knop = knopVoor(id)

            assertTrue("disabled" in knop, "de knop bij keuzelijst $id staat bij het laden al aan")
            assertTrue(
                "data-wacht-op-lijst=\"ja\"" in knop,
                "de knop bij keuzelijst $id noemt niet waarop hij wacht",
            )
        }
    }

    /**
     * Twee eigenaars die allebei rechtstreeks `disabled` schrijven, geven elkaars knop vrij: het
     * inrichten herhaalt zichzelf en kan een lopende aanlevering midden in de rit vrijgeven, waarna
     * een tweede druk hetzelfde bericht nog een keer aanlevert. Het script leidt de toestand daarom
     * op één plek af uit twee vlaggen.
     */
    @Test
    fun `alleen werkKnopBij zet een knop aan of uit`() {
        val schrijvers = Regex("""knop\.disabled\s*=""").findAll(script).count()

        // Eerst bewijzen dat de meting iets meet: dit patroon vindt elders in het script wél
        // meerdere plekken, dus een teller van 1 hierboven is een echte beperking.
        assertTrue(
            Regex("""keuze\.disabled\s*=""").findAll(script).count() > 1,
            "de meting vindt geen enkele andere schrijver; het patroon klopt niet meer",
        )

        assertEquals(1, schrijvers, "knop.disabled hoort alleen in werkKnopBij geschreven te worden")
        assertTrue(
            Regex("""knop\.disabled\s*=""").containsMatchIn(functie("werkKnopBij")),
            "de enige schrijver van knop.disabled staat niet in werkKnopBij",
        )
    }

    /**
     * Een uitlezing zonder tijdslimiet die blijft hangen, laat alles wat op dat antwoord wacht
     * onbeperkt staan — en juist de keuzelijsten hangen eraan. De limiet blijft onder de poll-lus,
     * anders stapelen de rondes op elkaar.
     */
    @Test
    fun `het uitlezen van de toestand kent een tijdslimiet korter dan de poll`() {
        val limiet = constante("LEES_TIMEOUT_MS")
        val poll = constante("POLL_MS")

        assertTrue(limiet < poll, "de tijdslimiet ($limiet ms) hoort onder de poll-lus ($poll ms) te blijven")
        assertTrue("signal" in functie("lees"), "lees() geeft zijn fetch geen signal mee en breekt dus nooit af")
    }

    /**
     * Een mislukte start herstelt zichzelf, maar wachten op de volgende poging is tijdens een demo
     * geen optie. De knop is de enige weg terug die zeker werkt; een refresh mag dat niet zijn.
     */
    @Test
    fun `een mislukte inrichting is ook met een knop opnieuw te proberen`() {
        assertTrue(
            "data-actie=\"omgeving-opnieuw\"" in paneel,
            "de opmaak draagt geen knop om het inrichten opnieuw te proberen",
        )
        assertTrue(
            """'omgeving-opnieuw': richtIn,""" in script,
            "het script koppelt de knop niet aan het opnieuw inrichten",
        )
        assertTrue(
            Regex("""const INRICHT_WACHT = \[[^]]+]""").containsMatchIn(script),
            "het script kent geen wachttijden voor een volgende poging",
        )
    }

    /**
     * `LOSSE_ACTIES[naam]()` gooit een TypeError op een naam die het script niet kent. Die vliegt
     * uit de listener: de knop doet niets, en niets zegt waarom. Het script vangt dat nu af met een
     * opmaakfout-melding, maar de naam hoort natuurlijk gewoon te bestaan.
     */
    @Test
    fun `elke losse actie in de opmaak bestaat in het script`() {
        val uitPaneel = Regex("""data-actie="([^"]+)"""").findAll(paneel).map { it.groupValues[1] }.toSet()
        val blok = Regex("""^const LOSSE_ACTIES = \{${'$'}(.*?)^};${'$'}""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(script)
            ?.groupValues?.get(1)
            .orEmpty()
        val sleutels = Regex("""^ {4}'?([\w-]+)'?:""", RegexOption.MULTILINE).findAll(blok).map { it.groupValues[1] }.toSet()

        assertTrue(uitPaneel.isNotEmpty(), "geen enkele data-actie gevonden in de opmaak")
        assertTrue(sleutels.isNotEmpty(), "het LOSSE_ACTIES-blok niet gevonden in het script")
        assertEquals(emptySet<String>(), uitPaneel - sleutels, "knop met een actie die het script niet kent")
    }

    /** De opmaak van het element met deze id, van `<` tot en met de eerste `>`. */
    private fun element(id: String): String {
        val gevonden = Regex("""<\w+[^>]*\sid="$id"[^>]*>""").find(paneel)?.value

        assertTrue(gevonden != null, "element $id niet gevonden in de opmaak")

        // Een <select> draagt zijn opties ná die eerste `>`; die horen erbij, want de placeholder
        // erin is wat de bediener ziet zolang de lijst niet gevuld is.
        return if (gevonden!!.startsWith("<select")) {
            Regex("""<select[^>]*\sid="$id".*?</select>""", RegexOption.DOT_MATCHES_ALL).find(paneel)!!.value
        } else {
            gevonden
        }
    }

    /** De knop wier adres dit veld invult. */
    private fun knopVoor(id: String): String {
        // `[^>]` sluit `>` uit maar niet de regeleinden: een knop in deze opmaak loopt over
        // meerdere regels, en zijn adres staat zelden op de eerste.
        val knop = Regex("""<button[^>]*data-pad="[^"]*\{$id}[^"]*"[^>]*>""").find(paneel)?.value

        assertTrue(knop != null, "geen knop gevonden die veld $id invult")

        return knop!!
    }

    /** De body van een functie op het hoogste niveau van `bediening.js`. */
    private fun functie(naam: String): String {
        val body = Regex("""^(?:async )?function $naam\([^)]*\) \{$(.*?)^}$""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(script)
            ?.groupValues?.get(1)

        assertTrue(body != null, "de functie $naam niet gevonden in het script")

        return body!!
    }

    private fun constante(naam: String): Int {
        val waarde = Regex("""^const $naam = (\d+);$""", RegexOption.MULTILINE).find(script)?.groupValues?.get(1)

        assertTrue(waarde != null, "de constante $naam niet gevonden in het script")

        return waarde!!.toInt()
    }
}
