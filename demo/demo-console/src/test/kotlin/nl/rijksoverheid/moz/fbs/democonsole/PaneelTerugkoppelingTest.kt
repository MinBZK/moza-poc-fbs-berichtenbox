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
 * Docker. Een browser is er niet en deze build kent geen JS-runner, dus getoetst wordt wat het
 * gedrag draagt: de attributen in de opmaak en de vorm van het script. Wat een statische toets niet
 * bereikt — de zinsbouw van `opsom()` bij één, twee en meer namen, en de enkelvoud/meervoud-keuze
 * in de melding — blijft daarmee ongedekt.
 */
class PaneelTerugkoppelingTest {

    private val paneel: String = PaneelBestanden.paneel()

    private val script: String = PaneelBestanden.script()

    private val stijl: String = PaneelBestanden.stijl()

    /** De id's die een knop-adres invult, bijvoorbeeld `berichtAantal` uit `?aantal={berichtAantal}`. */
    private val veldIds: Set<String> = Regex("""data-pad="([^"]+)"""")
        .findAll(paneel)
        .flatMap { Regex("""\{(\w+)}""").findAll(it.groupValues[1]) }
        .map { it.groupValues[1] }
        .toSet()

    @Test
    fun `de opmaak draagt knoppen die een veld invullen`() {
        // Zonder deze assertie loopt de test over `data-veldnaam` hieronder over een lege
        // verzameling en slaagt hij altijd, ook als dat attribuut overal verdwijnt.
        assertTrue(veldIds.isNotEmpty(), "geen enkel {veld} gevonden in een knop-adres")
    }

    // ------------------------------------------------------------ een veld dat niet ingevuld is

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

    /** Het attribuut in de opmaak zegt niets zolang het script het niet leest. */
    @Test
    fun `het script noemt het veld bij die naam`() {
        assertTrue(
            "dataset.veldnaam" in functie("veldnaam"),
            "veldnaam() leest data-veldnaam niet en valt dus altijd terug op de id",
        )
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
     * De kern van dit paneel: een knop die niet kán, zegt dat langs beide kanalen. Eén gedeelde
     * `null` uit `vulPadIn` laat `voerUit` terugkeren vóór de melding en het merkteken.
     *
     * Getoetst wordt daarom allebei: dat `vulPadIn` de drie oorzaken uit elkaar houdt, en dat elke
     * vroege uitgang in `voerUit` een merkteken én een melding achterlaat. Het aantal uitgangen en
     * het aantal merktekens moeten gelijk zijn, anders is er een pad zonder terugkoppeling
     * bijgekomen.
     */
    @Test
    fun `een veld dat niet ingevuld is levert een melding en een merkteken op`() {
        val invullen = functie("vulPadIn")

        assertTrue("opmaakfout:" in invullen, "vulPadIn onderscheidt een veld dat de opmaak niet draagt niet")
        assertTrue("fout:" in invullen, "vulPadIn geeft geen reden terug bij een leeg of ongeldig veld")
        assertTrue("return null" !in invullen, "vulPadIn geeft nog steeds een kaal null terug")

        // Alles vóór de eerste regel van de echte actie: precies de uitgangen die zonder antwoord
        // kunnen eindigen.
        val voorbereiding = functie("voerUit").substringBefore("bezig += 1;")
        val uitgangen = Regex("""^\s+return;$""", RegexOption.MULTILINE).findAll(voorbereiding).count()
        val merktekens = Regex("""zetUitkomst\(knop, 'mislukt'\)""").findAll(voorbereiding).count()

        assertTrue(uitgangen > 0, "voerUit kent geen vroege uitgang meer; klopt deze test nog?")
        assertEquals(uitgangen, merktekens, "een vroege uitgang in voerUit laat geen merkteken op de knop achter")
        assertTrue("meldOpmaakfout(" in voorbereiding, "een kapotte opmaak levert geen melding op")
        assertTrue("toonMelding(" in voorbereiding, "een leeg of ongeldig veld levert geen melding op")

        // De tekst zelf, want een lege melding is net zo stil als geen melding.
        assertTrue("'vul eerst '" in invullen, "de melding bij een leeg veld draagt geen tekst")
        assertTrue("' niet geldig'" in invullen, "de melding bij een ongeldig veld draagt geen tekst")
    }

    /**
     * De ontdubbeling bestaat voor de inricht-lus, die een ontbrekend element bij elke poging opnieuw
     * langsbrengt. Zou ze ook voor een druk op een knop gelden, dan is de tweede druk weer even stil
     * als de eerste was — de storing waar deze wijziging over gaat.
     */
    @Test
    fun `alleen de herhalende lus wordt ontdubbeld, niet een druk op een knop`() {
        val melden = functie("meldOpmaakfout")

        assertTrue("uitDeLus && gemeldeOpmaakfouten.has(wat)" in melden, "de ontdubbeling kent het verschil niet")
        assertTrue("toonMelding(" in melden, "een opmaakfout komt niet verder dan de console")

        // De aanroepen uit een klik geven die vlag niet mee; die uit de lus wel.
        assertTrue(
            "meldOpmaakfout(invoer.opmaakfout)" in functie("voerUit"),
            "een veld dat de opmaak mist wordt maar één keer gemeld",
        )
        assertTrue(
            Regex("""meldOpmaakfout\('de keuzelijst ' \+ keuzeId, true\)""").containsMatchIn(script),
            "de keuzelijst uit de lus wordt niet ontdubbeld",
        )

        // Anoniem melden maakt twee dode bedieningen tot één melding, en de tweede verdwijnt dan in
        // de ontdubbeling.
        assertTrue("'een keuzelijst'" !in script, "een ontbrekende keuzelijst wordt niet bij naam genoemd")
    }

    // ------------------------------------------------------------ een knop die nog niet kan

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
            // Op het element zelf en niet ergens in de inhoud: een `disabled` op een <option>
            // eronder zou anders ook voldoen.
            assertTrue(
                Regex("""<select[^>]*\sid="$id"[^>]*\sdisabled""").containsMatchIn(paneel),
                "keuzelijst $id staat bij het laden al aan",
            )
            assertTrue("value=\"\"" in element(id), "keuzelijst $id draagt geen optie met een lege waarde")

            val knop = knopVoor(id)

            assertTrue(Regex("""<button[^>]*\sdisabled""").containsMatchIn(knop), "de knop bij $id staat al aan")
            assertTrue(
                "data-wacht-op-lijst=\"ja\"" in knop,
                "de knop bij keuzelijst $id noemt niet waarop hij wacht",
            )
        }
    }

    /**
     * Een optie zonder `value` draagt haar tékst als waarde. Zonder deze regel stuurt de knop
     * `?persona=persona-lijst niet op te halen` en komt er een 404 terug die naar de inrichting van
     * de persona's wijst in plaats van naar de mislukte uitlezing.
     */
    @Test
    fun `een keuzelijst zonder bruikbare opties draagt een lege waarde`() {
        val vullen = functie("vulKeuze")

        assertTrue(Regex("""\.value = '';""").containsMatchIn(vullen), "de terugvaloptie krijgt geen lege waarde")

        // In de lege-tak zelf: de vrijgave hoger in dezelfde functie zou een assertie over het
        // geheel al tevreden stellen, en dan staat de knop boven een lege lijst gewoon aan.
        assertTrue(
            "zetWachtOpLijst(knop, true)" in vullen.substringAfter("if (!opties.length) {"),
            "een lege keuzelijst laat de knop aan",
        )
    }

    /**
     * Twee eigenaars die allebei rechtstreeks `disabled` schrijven, geven elkaars knop vrij: het
     * inrichten herhaalt zichzelf en kan een lopende aanlevering midden in de rit vrijgeven, waarna
     * een tweede druk hetzelfde bericht nog een keer aanlevert. Het script leidt de toestand daarom
     * op één plek af. Dit gaat over de actieknoppen; de knop in het inrichtingsblok heeft maar één
     * eigenaar en valt er buiten.
     */
    @Test
    fun `alleen werkKnopBij zet een actieknop aan of uit`() {
        // `=(?!=)` en niet `=`: anders telt een toekomstige vergelijking `knop.disabled ===` mee en
        // faalt deze test op code die niets schrijft.
        val schrijft = Regex("""knop\.disabled\s*=(?!=)""")

        // Eerst bewijzen dat de meting iets meet: ditzelfde patroon vindt op de keuzelijst wél
        // meerdere plekken, dus een teller van 1 hierboven is een echte beperking.
        assertTrue(
            Regex("""keuze\.disabled\s*=(?!=)""").findAll(script).count() > 1,
            "de meting vindt geen enkele andere schrijver; het patroon klopt niet meer",
        )

        assertEquals(1, schrijft.findAll(script).count(), "knop.disabled hoort alleen in werkKnopBij te staan")
        assertTrue(schrijft.containsMatchIn(functie("werkKnopBij")), "de enige schrijver staat niet in werkKnopBij")
    }

    /**
     * Eén schrijver is niet genoeg: die moet ook allebei de redenen wegen. Weegt hij alleen de
     * lopende actie, dan blijft de teller op één en komt precies de bug terug die deze splitsing
     * moest voorkomen.
     */
    @Test
    fun `werkKnopBij weegt allebei de redenen, en allebei worden ze gezet`() {
        val afleiding = functie("werkKnopBij")

        assertTrue("actieLoopt" in afleiding, "werkKnopBij weegt de lopende actie niet mee")
        assertTrue("wachtOpLijst" in afleiding, "werkKnopBij weegt de wachtende keuzelijst niet mee")

        assertTrue("zetActieLoopt(knop, true)" in functie("voerUit"), "voerUit meldt de lopende actie niet")
        assertTrue("zetActieLoopt(knop, false)" in functie("voerUit"), "voerUit meldt het einde van de actie niet")
        assertTrue("zetWachtOpLijst(" in functie("vulKeuze"), "vulKeuze geeft de knop niet vrij")
        assertTrue("zetWachtOpLijst(" in functie("meldLijstOnbekend"), "een onleesbare lijst zet de knop niet uit")
    }

    // ------------------------------------------------------------ een uitvraag die blijft hangen

    /**
     * Een uitlezing zonder timeout die blijft hangen, laat alles wat op dat antwoord wacht
     * onbeperkt staan — en juist de keuzelijsten hangen eraan. De grens blijft onder de poll-lus,
     * anders stapelen de rondes op elkaar.
     */
    @Test
    fun `het uitlezen van de toestand kent een timeout korter dan de poll`() {
        val limiet = constante("LEES_TIMEOUT_MS")
        val poll = constante("POLL_MS")
        val uitlezen = functie("lees")

        assertTrue(limiet < poll, "de timeout ($limiet ms) hoort onder de poll-lus ($poll ms) te blijven")

        // Op de fetch zelf: het woord "signal" staat ook in de foutmelding eronder, en die breekt
        // niets af.
        assertTrue(
            Regex("""fetch\([^)]*signal:""").containsMatchIn(uitlezen),
            "lees() geeft zijn fetch geen signal mee en breekt dus nooit af",
        )
        assertTrue("clearTimeout" in uitlezen, "lees() laat zijn timer staan na een geslaagde uitlezing")

        // Op de fout zelf en met de status erbij: `signal.aborted` staat ook op true wanneer de timer
        // net ná een echte fout afgaat, en een status die tijdens het lezen van de body verloren gaat
        // is juist het enige aanknopingspunt.
        assertTrue("AbortError" in uitlezen, "lees() leidt een afgebroken uitlezing af uit het signal")
        assertTrue("status" in uitlezen, "lees() houdt de HTTP-status niet vast")
    }

    /**
     * De wachttijd loopt op zolang het misgaat. Eén vaste wachttijd zou een onbereikbare omgeving
     * elke paar seconden opnieuw bevragen; de laatste waarde geldt voor alles daarna, dus die klem
     * hoort erbij.
     */
    @Test
    fun `de wachttijden tussen twee pogingen lopen op`() {
        val wachttijden = getallen("INRICHT_WACHT")

        assertTrue(wachttijden.size > 1, "één wachttijd is geen oplopende reeks")
        assertEquals(wachttijden.sorted().distinct(), wachttijden, "de wachttijden lopen niet strikt op")
        assertTrue(
            "Math.min(inrichtPoging, INRICHT_WACHT.length - 1)" in functie("volgendeWachttijd"),
            "de reeks wordt niet geklemd; voorbij de laatste wachttijd valt de wachttijd weg",
        )

        // De README noemt deze reeks in seconden; loopt die uiteen met de code, dan leest de
        // bediener een ritme dat het paneel niet aanhoudt.
        val alinea = PaneelBestanden.leesmij().substringAfter("Een druk op een knop").substringBefore("\n\n")

        // Op hele getallen: een kale substring vindt "2" ook in "25" en koppelt dan niets.
        val ontbreekt = (wachttijden + constante("LEES_TIMEOUT_MS"))
            .map { it / 1000 }
            .filterNot { Regex("""(?<!\d)$it(?!\d)""").containsMatchIn(alinea) }

        assertEquals(emptyList<Int>(), ontbreekt, "de README noemt deze seconden niet")
    }

    /**
     * Zonder wissen laat een nieuwe poging terwijl er al een poging gepland stond twee timers
     * achter, en verdubbelt het aantal pogingen bij elke ronde.
     */
    @Test
    fun `een geplande poging wordt eerst gewist`() {
        assertTrue(
            "clearTimeout(inrichtTimer)" in functie("planInrichting"),
            "planInrichting wist de vorige timer niet",
        )
        assertTrue("planInrichting(null)" in functie("richtIn"), "richtIn laat een geplande poging naast zich staan")
    }

    /**
     * `pasOmgevingToe` zegt of de omgeving gelezen is; zonder dat antwoord is `gelukt` altijd
     * `undefined` en blijft het foutblok staan terwijl het paneel eeuwig doorprobeert. En zonder de
     * teller terug te zetten begint een tweede storing meteen op de langste wachttijd.
     */
    @Test
    fun `een geslaagde inrichting meldt zich en zet de teller terug`() {
        assertTrue(
            "return omgeving !== null" in functie("pasOmgevingToe"),
            "pasOmgevingToe zegt niet of de omgeving gelezen is",
        )
        assertTrue("inrichtPoging = 0" in functie("richtIn"), "richtIn zet de teller na een geslaagde poging niet terug")
    }

    /**
     * Een mislukte start herstelt zichzelf, maar wachten op de volgende poging is tijdens een demo
     * geen optie. De knop is de weg terug die zeker werkt; een refresh mag dat niet zijn.
     */
    @Test
    fun `een mislukte inrichting is ook met een knop opnieuw te proberen`() {
        assertTrue(
            "data-actie=\"omgeving-opnieuw\"" in paneel,
            "de opmaak draagt geen knop om het inrichten opnieuw te proberen",
        )

        // Verborgen tot er iets mis is: anders opent elke demo met een rood foutblok.
        assertTrue(
            Regex("""<div[^>]*\sid="inrichting"[^>]*\shidden""").containsMatchIn(paneel),
            "het foutblok van de inrichting staat bij het laden open",
        )
    }

    /**
     * De knop die uit de stilte moet breken, mag zelf niet stil zijn. Een uitlezing duurt tot de
     * timeout, dus zonder een merkbare wijziging bij de start lijkt een druk op de knop seconden
     * lang niets te doen — en zonder tekst bij een mislukking daarna nog steeds niet.
     */
    @Test
    fun `een druk op die knop verandert meteen iets, en meldt ook een mislukking`() {
        val inrichten = functie("richtIn")

        assertTrue("zetInrichtenBezig(true)" in inrichten, "de knop geeft geen teken dat de poging loopt")

        // Per tak meten en niet over de hele body: `toonInrichtingsfout` staat ook in de catch, dus
        // een assertie over het geheel blijft groen als juist de mislukkingstak leeggehaald wordt.
        val mislukt = inrichten.substringAfter("if (!gelukt) {").substringBefore("return;")

        assertTrue("toonInrichtingsfout(" in mislukt, "een mislukte uitlezing laat de tekst onveranderd")
        assertTrue("planInrichting(wacht)" in mislukt, "na een mislukking wordt geen nieuwe poging gepland")
        assertTrue("toonInrichtingsfout(" in inrichten.substringAfter("} catch"), "de catch-tak meldt niets")

        val geslaagd = inrichten.substringAfter("return;").substringBefore("} catch")

        assertTrue("toonInrichting(false)" in geslaagd, "na een geslaagde poging blijft het foutblok staan")
        assertTrue("toonMelding(" in geslaagd, "een herstel na een storing wordt niet gemeld")

        assertTrue(
            "textContent" in functie("toonInrichtingsfout"),
            "toonInrichtingsfout schrijft geen tekst en is dus niet te zien",
        )
        assertTrue("disabled" in functie("zetInrichtenBezig"), "de knop blijft indrukbaar tijdens zijn eigen poging")
        assertTrue("Bezig" in functie("zetInrichtenBezig"), "een lopende poging is niet aan de tekst te zien")
    }

    /**
     * Twee pogingen naast elkaar overschrijven elkaars uitkomst — en de laatste die terugkomt is
     * niet per se de meest actuele. En een handmatige poging hoort de automatische wachttijd niet op
     * te jagen: die loopt op omdat een console die weg is meestal een tijdje weg blijft.
     */
    @Test
    fun `pogingen sluiten elkaar uit en handmatige pogingen tellen niet mee`() {
        val inrichten = functie("richtIn")

        // Alle drie de plekken apart: alleen de naam toetsen laat zowel een verdwenen guard door als
        // een vlag die nooit meer op false gaat — een knop die vanaf dan alles afwijst.
        assertTrue(
            Regex("""if \(inrichtLoopt\)[^}]*return;""").containsMatchIn(inrichten),
            "richtIn laat twee pogingen naast elkaar lopen",
        )
        assertTrue("inrichtLoopt = true;" in inrichten.substringBefore("try {"), "de vlag gaat niet aan")
        assertTrue(
            "inrichtLoopt = false" in inrichten.substringAfter("} finally {"),
            "de vlag gaat niet uit na de poging; de knop wijst daarna alles af",
        )
        assertTrue(
            Regex("""if \(!metHand\)\s*inrichtPoging \+= 1;""").containsMatchIn(inrichten),
            "een druk op de knop jaagt de wachttijd op",
        )

        // De bedrading hoort erbij: een kale functiereferentie krijgt geen argument mee, dus zou
        // `metHand` undefined zijn en verliest de knop alles wat hem van de automatische lus
        // onderscheidt.
        assertTrue(
            "'omgeving-opnieuw': () => richtIn(true)," in script,
            "de knop is niet als handmatige poging bedraad",
        )
    }

    /**
     * Het inrichtingsblok staat ín het paneel, en een ingeklapt paneel is `display: none`. Zonder
     * merkteken op de klap-knop is een half ingericht paneel dan nergens aan te zien.
     */
    @Test
    fun `een ingeklapt paneel laat zien dat het niet compleet is`() {
        assertTrue("markeerKlap(" in functie("toonInrichting"), "het foutblok markeert de klap-knop niet")
        assertTrue("dataset.letOp" in functie("markeerKlap"), "markeerKlap zet geen merkteken")
        assertTrue("dataset.letOp" in functie("werkKlapBij"), "het merkteken overleeft een in- of uitklap niet")
        assertTrue(
            Regex("""#klap\[data-let-op="true"]::after""").containsMatchIn(stijl),
            "de opmaak toont geen merkteken op de klap-knop",
        )
    }

    /**
     * Een throw uit de click-listener of een afgewezen promise uit een fire-and-forget-aanroep komt
     * anders alleen in de browserconsole terecht — en wie een demo geeft heeft geen devtools open.
     */
    @Test
    fun `het paneel meldt ook wat buiten een eigen vangnet omvalt`() {
        assertTrue("addEventListener('error'" in script, "een throw uit de listener blijft onopgemerkt")
        assertTrue("addEventListener('unhandledrejection'" in script, "een afgewezen promise blijft onopgemerkt")

        // Vóór de bedrading: die zoekt zelf elementen op en kan dus zélf omvallen — dan was het
        // vangnet nog niet geregistreerd en stopt het script zonder een woord.
        assertTrue(
            script.indexOf("addEventListener('error'") < script.indexOf("herstelStand();"),
            "het vangnet staat ná de bedrading die het zou moeten dekken",
        )

        // De meldingsbalk is vijf elementen; valt `toonMelding` over één ervan, dan sleept hij het
        // vangnet mee dat hem net aanriep.
        val tonen = functie("toonMelding")

        assertTrue("!meldingTekst" in tonen, "toonMelding bewaakt alleen de balk zelf en niet zijn regels")

        // De poll draait elke paar seconden; zonder ontdubbeling schrijft één storing daarin steeds
        // opnieuw over de uitkomst van de laatste actie heen.
        assertTrue("laatsteVangnetfout" in functie("meldVangnet"), "het vangnet ontdubbelt niet")
    }

    // ------------------------------------------------------------ bedrading opmaak en script

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

        // Een kale lookup levert bij een naam als `toString` een geërfde functie op, die dan wordt
        // aangeroepen in plaats van gemeld.
        assertTrue("Object.hasOwn(LOSSE_ACTIES" in script, "een onbekende actie kan een geërfde functie oppikken")
    }

    /**
     * De module-lookups geven stil `null` bij een hernoemde id, en de meeste gebruikers ervan
     * dereferencen zonder toets: de eerste poll of de eerste klik loopt dan stuk op een TypeError.
     * Het vangnet meldt dat als "onverwachte fout", en dat vertelt de bediener niet wélk element weg
     * is — deze test wel.
     */
    @Test
    fun `elk element dat het script opzoekt bestaat in de opmaak`() {
        val opgezocht = Regex("""getElementById\('([^']+)'\)""").findAll(script).map { it.groupValues[1] }.toSet()
        val aanwezig = Regex("""\sid="([^"]+)"""").findAll(paneel).map { it.groupValues[1] }.toSet()

        assertTrue(opgezocht.isNotEmpty(), "het script zoekt geen enkel element op; klopt dit patroon nog?")
        assertEquals(emptySet<String>(), opgezocht - aanwezig, "het script zoekt een element dat de opmaak niet draagt")
    }

    /**
     * De opmaak van het element met deze id, van `<` tot en met de eerste `>`; voor een `<select>`
     * inclusief zijn opties.
     */
    private fun element(id: String): String {
        val gevonden = Regex("""<\w+[^>]*\sid="$id"[^>]*>""").find(paneel)?.value

        assertTrue(gevonden != null, "element $id niet gevonden in de opmaak")

        // Een <select> draagt zijn opties ná die eerste `>`; die horen erbij, want de terugvaloptie
        // erin is wat de bediener ziet zolang de lijst niet gevuld is.
        return if (gevonden!!.startsWith("<select")) {
            Regex("""<select[^>]*\sid="$id".*?</select>""", RegexOption.DOT_MATCHES_ALL).find(paneel)!!.value
        } else {
            gevonden
        }
    }

    /** De knop wier adres dit veld invult. */
    private fun knopVoor(id: String): String {
        // `[^>]` sluit `>` uit maar niet de regeleinden: een knop in deze opmaak loopt over meerdere
        // regels, en zijn adres staat zelden op de eerste.
        val knop = Regex("""<button[^>]*data-pad="[^"]*\{$id}[^"]*"[^>]*>""").find(paneel)?.value

        assertTrue(knop != null, "geen knop gevonden die veld $id invult")

        return knop!!
    }

    /** De body van een functie op het hoogste niveau van `bediening.js`. */
    private fun functie(naam: String): String {
        val body = Regex("""^(?:async )?function $naam\([^)]*\) \{${'$'}(.*?)^}${'$'}""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(script)
            ?.groupValues?.get(1)

        assertTrue(body != null, "de functie $naam niet gevonden in het script")

        return body!!
    }

    private fun constante(naam: String): Int {
        val waarde = Regex("""^const $naam = ([\d_]+);${'$'}""", RegexOption.MULTILINE).find(script)?.groupValues?.get(1)

        assertTrue(waarde != null, "de constante $naam niet gevonden in het script")

        return waarde!!.replace("_", "").toInt()
    }

    private fun getallen(naam: String): List<Int> {
        val reeks = Regex("""^const $naam = \[([^]]*)];${'$'}""", RegexOption.MULTILINE).find(script)?.groupValues?.get(1)

        assertTrue(reeks != null, "de reeks $naam niet gevonden in het script")

        return Regex("""[\d_]+""").findAll(reeks!!).map { it.value.replace("_", "").toInt() }.toList()
    }
}
