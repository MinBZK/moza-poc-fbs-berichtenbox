package nl.rijksoverheid.moz.fbs.democonsole

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Bewaakt het Info-blad: het eerste dat de bediener ziet, met de toestand van de demo-stack al
 * ingevuld in plaats van achter knoppen.
 *
 * De blokken hangen aan elkaar via losse sleutels — `INFO_BLOKKEN` in het script, `info-<sleutel>-…`
 * in de opmaak — die de browser pas bij het tekenen samenvoegt. Een blok dat aan één kant hernoemd
 * wordt, blijft dan stil leeg, of meldt pas tijdens de demo dat zijn opmaak ontbreekt.
 *
 * Bewust géén `@QuarkusTest`: dit leest de bestanden rechtstreeks van schijf en draait dus zonder
 * Docker. Er is geen JS-runtime in de build, dus getoetst wordt de vorm van opmaak en script.
 */
class PaneelInfoTest {

    private val paneel: String = PaneelBestanden.paneel()

    private val script: String = PaneelBestanden.script()

    private val stijl: String = PaneelBestanden.stijl()

    /** De sleutels van `INFO_BLOKKEN`, in de volgorde van het script. */
    private val blokken: List<String> = Regex("""^const INFO_BLOKKEN = \{$(.*?)^};$""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        .find(script)
        ?.groupValues?.get(1)
        ?.let { blok -> Regex("""^ {4}(\w+):""", RegexOption.MULTILINE).findAll(blok).map { it.groupValues[1] }.toList() }
        .orEmpty()

    @Test
    fun `Info is het eerste tabblad en staat zonder bewaarde stand open`() {
        val tabs = Regex("""<button[^>]*role="tab"[^>]*>""").findAll(paneel).map { it.value }.toList()

        assertTrue(tabs.isNotEmpty(), "geen tabbladen gevonden; klopt dit patroon nog?")
        assertTrue("""id="tab-info"""" in tabs.first(), "het eerste tabblad is niet Info maar ${tabs.first()}")
        assertTrue("""aria-selected="true"""" in tabs.first(), "Info staat niet standaard geselecteerd")
        assertEquals(1, tabs.count { """aria-selected="true"""" in it }, "meer dan één tabblad staat geselecteerd")

        val bladen = Regex("""<section role="tabpanel" id="([\w-]+)"[^>]*>""").findAll(paneel).toList()

        assertTrue(bladen.isNotEmpty(), "geen tabbladinhoud gevonden; klopt dit patroon nog?")

        bladen.forEach { blad ->
            val open = " hidden" !in blad.value

            assertEquals(blad.groupValues[1] == "blad-info", open, "${blad.groupValues[1]} staat bij het laden ${if (open) "open" else "dicht"}")
        }
    }

    @Test
    fun `elk info-blok heeft inhoud, een tijdlabel en een ververs-knop in de opmaak`() {
        assertEquals(
            listOf("berichten", "stroom", "storingen", "componenten", "simulator", "omgeving", "personas"),
            blokken,
            "INFO_BLOKKEN is veranderd; klopt deze test nog?",
        )

        blokken.forEach { sleutel ->
            assertTrue("""id="info-$sleutel-inhoud"""" in paneel, "info-blok $sleutel mist zijn inhoud in de opmaak")
            assertTrue("""id="info-$sleutel-tijd"""" in paneel, "info-blok $sleutel mist zijn tijdlabel in de opmaak")

            val blok = Regex("""data-info="$sleutel".*?(?=data-info="|</section>)""", RegexOption.DOT_MATCHES_ALL).find(paneel)?.value

            assertTrue(blok != null, "geen element met data-info=\"$sleutel\" in de opmaak")
            assertTrue(Regex("""<button[^>]*data-actie="[^"]+"""").containsMatchIn(blok!!), "info-blok $sleutel heeft geen ververs-knop")
        }
    }

    /**
     * Het tijdlabel verandert elke seconde. Met `aria-live` erop leest een schermlezer dat ook elke
     * seconde voor, en komt de melding van een actie er niet meer tussen.
     */
    @Test
    fun `een tijdlabel kondigt zichzelf niet aan`() {
        val labels = Regex("""<[^>]*id="info-\w+-tijd"[^>]*>""").findAll(paneel).map { it.value }.toList()

        assertEquals(blokken.size, labels.size, "niet elk info-blok heeft precies één tijdlabel")
        assertTrue(labels.none { "aria-live" in it }, "een tijdlabel draagt aria-live")
    }

    /**
     * De simulatorlijst is het zwaarste antwoord en verandert alleen door een actie; hij hoort niet in
     * het ritme van de toestandsbalk mee. De tik loopt vaker dan de poll, en een simulatoruitlezing die
     * blijft hangen stapelt niet op de volgende. Een omgeving zonder simulator vraagt er niet naar: dat
     * levert alleen een 404 per halve minuut op.
     */
    @Test
    fun `de simulatorlijst ververst trager dan de poll, en de tik sneller`() {
        val simulator = constante("SIMULATOR_INFO_MS")
        val poll = constante("POLL_MS")
        val tik = constante("TIK_MS")

        assertTrue(simulator > poll, "de simulatorlijst ($simulator ms) ververst niet trager dan de poll ($poll ms)")
        assertTrue(tik < poll, "de tik ($tik ms) loopt niet vaker dan de poll ($poll ms)")
        assertTrue(simulator > constante("LEES_TIMEOUT_MS"), "een hangende simulatoruitlezing stapelt op de volgende")

        val tikken = functie("tikInfo")

        assertTrue("document.hidden" in tikken, "de tik loopt ook door in een tab waar niemand kijkt")
        assertTrue("infoInBeeld()" in tikken, "de tik leest de simulator ook buiten het Info-blad")
        assertTrue("bezig === 0" in tikken, "de tik leest de simulator midden in een actie")
        assertTrue("heeftSimulator === true" in tikken, "de tik leest de simulator ook waar die niet is ingericht")
        assertTrue(
            Regex("""data-info="simulator"[^>]*\sdata-simulator""").containsMatchIn(paneel),
            "het simulatorblok verdwijnt niet in een omgeving zonder simulator",
        )
    }

    /**
     * Zonder `herstelInfo()` vóór het inrichten staat er na een refresh niets tot de eerste uitlezing
     * terug is; zonder de tik ververst de simulatorlijst nooit meer. Allebei blijven ze anders stil weg.
     */
    @Test
    fun `het blad herstelt zijn stand vóór het inrichten en tikt daarna`() {
        val herstel = regelIndex("""^herstelInfo\(\);$""")

        assertTrue(herstel < regelIndex("""^ {4}herstelStand\(\);$"""), "herstelInfo loopt pas na het herstellen van het tabblad")
        assertTrue(herstel < regelIndex("""^richtIn\(false\);$"""), "herstelInfo loopt pas na het inrichten")
        assertTrue("setInterval(tikInfo, TIK_MS);" in script, "de tik van het Info-blad wordt nooit gestart")
    }

    /**
     * `draaiTot` hangt aan een Promise. Een actie met een body tussen accolades geeft `undefined` terug,
     * en dan draait de ↻ niet meer terwijl er wel gelezen wordt.
     */
    @Test
    fun `elke ververs-actie van het Info-blad geeft zijn uitlezing terug`() {
        listOf(
            "'ververs-toestand': () => verversToestand(true),",
            "'ververs-simulator': () => verversSimulator(true),",
            "'omgeving-opnieuw': () => richtIn(true),",
        ).forEach { actie ->
            assertTrue(actie in script, "de actie $actie geeft zijn uitlezing niet terug")
        }
    }

    /** Na een actie kan het gedrag van de simulator net veranderd zijn; dan niet dertig seconden wachten. */
    @Test
    fun `een actie laat de simulatorlijst bij de volgende tik opnieuw lezen`() {
        assertTrue("simulatorVerlopen = true;" in functie("voerUit"), "een actie markeert de simulatorlijst niet als verlopen")
        assertTrue("tikInfo()" in functie("kiesTab"), "het openen van het Info-blad wacht op de volgende tik")
    }

    /**
     * Wat de Info-tab tekent, komt uit dezelfde uitlezingen als de chips; een blok dat een eigen
     * uitlezing krijgt, kan een ander getal tonen dan de balk erboven.
     */
    @Test
    fun `de toestand-blokken lezen mee met de toestandsbalk en de omgeving met het inrichten`() {
        val toestand = functie("verversToestand")

        listOf(
            "berichten" to "status",
            "stroom" to "tempo",
            "storingen" to "storingen",
            "componenten" to "bereikbaarheid",
        ).forEach { (sleutel, antwoord) ->
            assertTrue("werkInfoBij('$sleutel', $antwoord);" in toestand, "info-blok $sleutel leest niet mee met de toestandsbalk")
        }

        assertTrue("werkInfoBij('omgeving', omgeving);" in functie("pasOmgevingToe"), "het omgevingsblok leest niet mee met het inrichten")
        assertTrue("werkInfoBij('personas', omgeving);" in functie("pasOmgevingToe"), "het persona-blok leest niet mee met het inrichten")
        assertTrue(
            "werkInfoBij('simulator', bruikbaar ? magazijnen : null);" in functie("verversSimulator"),
            "een simulatorlijst in een onverwachte vorm overschrijft de laatste stand",
        )

        // Een sleutel die INFO_BLOKKEN niet kent, geeft pas in de browser een TypeError.
        val aangeroepen = Regex("""werkInfoBij\('(\w+)'""").findAll(script).map { it.groupValues[1] }.toSet()

        assertEquals(emptySet<String>(), aangeroepen - blokken.toSet(), "werkInfoBij met een sleutel die INFO_BLOKKEN niet kent")
    }

    /**
     * Een mislukte uitlezing mag de laatste bekende stand niet wissen: juist tijdens een storing wil je
     * die nog zien. Het tijdlabel zegt dat hij verouderd is.
     */
    @Test
    fun `een mislukte uitlezing laat de vorige inhoud staan`() {
        val bijwerken = functie("werkInfoBij")
        val mislukt = bijwerken.substringAfter("if (antwoord === null) {").substringBefore("} else {")

        assertTrue("stand.mislukt = true;" in mislukt, "een mislukte uitlezing wordt niet als mislukt vastgelegd")
        assertFalse("tekenInfo(" in mislukt, "een mislukte uitlezing tekent het blok opnieuw")
        assertFalse("inhoud" in mislukt, "een mislukte uitlezing raakt de bewaarde inhoud")
    }

    /**
     * Het omgevingsantwoord draagt de persona's met hun `ontvanger` — een BSN of KVK-nummer — en de
     * Info-tab bewaart zijn antwoorden in `sessionStorage`. Alleen de labels mogen daarheen.
     */
    @Test
    fun `de cache bewaart geen identificatienummer van een persona`() {
        listOf("omgeving" to "omgevingInfo", "personas" to "personaInfo").forEach { (sleutel, snoeier) ->
            val snoeien = functie(snoeier)

            assertTrue(
                Regex("""$sleutel: \{[^}]*bewaarbaar: $snoeier""").containsMatchIn(script),
                "het blok $sleutel snoeit zijn antwoord niet vóór het bewaard wordt",
            )
            assertFalse("ontvanger" in snoeien, "$snoeier neemt de ontvanger van een persona over")
            assertFalse(Regex("""\.\.\.\s*(omgeving|persona)""").containsMatchIn(snoeien), "$snoeier kopieert een heel antwoord")
        }

        val bijwerken = functie("werkInfoBij")

        assertTrue("blok.bewaarbaar(antwoord)" in bijwerken, "werkInfoBij past bewaarbaar niet toe")
        assertTrue("stand.inhoud = nieuw;" in bijwerken, "werkInfoBij bewaart iets anders dan het gesnoeide antwoord")

        // Een vaste set: een veld dat erbij komt, hoort hier bewust te worden goedgekeurd.
        val velden = Regex("""^ {8}(\w+):""", RegexOption.MULTILINE)
            .findAll(functie("omgevingInfo").substringAfterLast("return {"))
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(setOf("storingen", "simulator", "sessiecache"), velden, "omgevingInfo bewaart andere velden dan het blok toont")

        // Per persona precies het label en de vlag. De id koppelt de twee lijsten, maar gaat niet mee.
        val perPersona = Regex("""\(\{([^}]*)}\)""").findAll(functie("personaInfo")).map { it.groupValues[1] }.toList()

        assertEquals(1, perPersona.size, "personaInfo bouwt een persona niet op precies één plek op")
        assertEquals(
            setOf("label", "metMagazijn"),
            Regex("""(\w+):""").findAll(perPersona.single()).map { it.groupValues[1] }.toSet(),
            "personaInfo bewaart per persona meer dan het label en de vlag",
        )

        assertEquals(1, Regex("""setItem\(INFO_SLEUTEL""").findAll(script).count(), "de info-cache wordt op meer dan één plek geschreven")
        assertTrue("setItem(INFO_SLEUTEL" in functie("bewaarInfo"), "de info-cache wordt buiten bewaarInfo geschreven")
    }

    /** De namen en OIN's in de simulatorlijst komen van buiten; als tekst en nooit als opmaak. */
    @Test
    fun `het paneel zet nergens opmaak uit een antwoord`() {
        assertFalse("innerHTML" in script, "het script schrijft opmaak via innerHTML")
        assertFalse("insertAdjacentHTML" in script, "het script schrijft opmaak via insertAdjacentHTML")
    }

    /**
     * De telling per modus volgt een vaste volgorde. Een modus die de simulator erbij krijgt, valt
     * niet weg — die komt achteraan — maar hoort wel een plek in die volgorde te krijgen.
     */
    @Test
    fun `de modusvolgorde kent precies de modi van de simulator`() {
        val volgorde = Regex("""^const MODUS_VOLGORDE = \[([^]]*)];$""", RegexOption.MULTILINE).find(script)?.groupValues?.get(1)

        assertTrue(volgorde != null, "MODUS_VOLGORDE niet gevonden in het script")

        val uitScript = Regex("""'([A-Z_]+)'""").findAll(volgorde!!).map { it.groupValues[1] }.toSet()
        val bron = File("../magazijn-simulator/src/main/kotlin/nl/rijksoverheid/moz/fbs/magazijnsimulator/gedrag/Gedrag.kt")

        assertTrue(bron.isFile, "Gedrag.kt niet gevonden op ${bron.absolutePath}")

        val enum = Regex("""enum class GedragModus\s*\{(.*?)\n}""", RegexOption.DOT_MATCHES_ALL).find(bron.readText())?.groupValues?.get(1)

        assertTrue(enum != null, "geen GedragModus-enum gevonden; klopt de vorm nog?")

        val modi = Regex("""^\s{4}([A-Z_]+),""", RegexOption.MULTILINE).findAll(enum!!).map { it.groupValues[1] }.toSet()

        assertTrue(modi.isNotEmpty(), "geen modi herkend in Gedrag.kt; klopt de vorm nog?")
        assertEquals(modi, uitScript, "MODUS_VOLGORDE loopt uit de pas met GedragModus")
    }

    /** Het draaiende teken is de enige terugkoppeling van ↻ tijdens het lezen; zonder regel draait er niets. */
    @Test
    fun `een ververs-knop draait zolang zijn uitlezing loopt`() {
        assertTrue("draaiTot(knop, loopt)" in script, "een losse actie die een uitlezing start, laat zijn knop niet draaien")
        assertTrue(""".infoknop[data-bezig="ja"]""" in stijl, "de stylesheet tekent een bezige ververs-knop niet")
    }

    private fun functie(naam: String): String {
        val body = Regex("""^(?:async )?function $naam\([^)]*\) \{$(.*?)^}$""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(script)
            ?.groupValues?.get(1)

        assertTrue(body != null, "de functie $naam niet gevonden in het script")

        return body!!
    }

    /** De positie van de eerste regel die dit patroon draagt, met een assertie dat er één is. */
    private fun regelIndex(patroon: String): Int {
        val gevonden = Regex(patroon, RegexOption.MULTILINE).find(script)

        assertTrue(gevonden != null, "geen regel gevonden voor $patroon")

        return gevonden!!.range.first
    }

    private fun constante(naam: String): Int {
        val waarde = Regex("""^const $naam = ([\d_]+);$""", RegexOption.MULTILINE).find(script)?.groupValues?.get(1)

        assertTrue(waarde != null, "de constante $naam niet gevonden in het script")

        return waarde!!.replace("_", "").toInt()
    }
}
