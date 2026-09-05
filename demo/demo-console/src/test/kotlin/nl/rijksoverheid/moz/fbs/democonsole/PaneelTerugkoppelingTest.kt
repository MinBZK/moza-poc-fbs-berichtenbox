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
            "veld.dataset.veldnaam || veld.id" in functie("veldnaam"),
            "veldnaam() valt terug op de id in plaats van het attribuut te gebruiken",
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
     * langsbrengt. Zou ze ook gelden voor wat een druk op een knop oplevert, dan is de tweede druk
     * weer even stil als de eerste — de storing waar deze wijziging over gaat. Twee dingen houden dat
     * tegen: de aanroepen uit een klik geven de vlag niet mee, en een handmatige poging leegt de set.
     */
    @Test
    fun `wat een druk op een knop oplevert wordt niet ontdubbeld`() {
        val melden = functie("meldOpmaakfout")

        // Binnen de vlag-tak en niet ergens in de functie: zonder die afbakening blijft deze test
        // groen als de `if (uitDeLus)` eromheen verdwijnt, en dan wordt ook een melding uit een klik
        // ontdubbeld — precies de stilte waar deze wijziging over gaat.
        val binnenVlag = melden.substringAfter("if (uitDeLus) {").substringBefore("    }")

        assertTrue("ontdubbeldInLus.has(wat)" in binnenVlag, "de ontdubbeling geldt ook voor een druk op een knop")
        assertTrue("ontdubbeldInLus.add(wat)" in binnenVlag, "een klik-melding komt in de ontdubbelset terecht")
        assertTrue("+ wat +" in melden, "de melding noemt niet wélk element ontbreekt")
        assertTrue("registreerPaneelfout(" in melden, "de storing wordt niet vastgelegd")

        val registreren = functie("registreerPaneelfout")

        assertTrue("openstaandePaneelfouten.add(wat)" in registreren, "de storing blijft nergens staan")
        assertTrue("markeerKlap(true)" in registreren, "een storing zet geen merkteken op de klap-knop")

        // De aanroepen uit een klik geven die vlag niet mee; die uit de lus wel.
        assertTrue(
            "meldOpmaakfout(invoer.opmaakfout)" in functie("voerUit"),
            "een veld dat de opmaak mist wordt maar één keer gemeld",
        )
        assertTrue(
            Regex("""meldOpmaakfout\(opsom\(mist\), true\)""").containsMatchIn(script),
            "de keuzelijst uit de lus wordt niet ontdubbeld",
        )

        // Beide helften apart: samen zeggen ze wélke bediening dood is, en dat is de reden dat deze
        // functie bestaat.
        val ontbrekend = functie("meldOntbrekendeLijst")

        assertTrue("if (!keuze) mist.push(" in ontbrekend, "een ontbrekende keuzelijst wordt niet genoemd")
        assertTrue("if (!knop) mist.push(" in ontbrekend, "een ontbrekende knop wordt niet genoemd")
        // Alleen wat de lus al zei: de storingen zelf blijven staan, want een uitlezing die een
        // ontbrekend invoerveld niet eens bekijkt, verhelpt het ook niet.
        assertTrue(
            "if (metHand) ontdubbeldInLus.clear();" in functie("richtIn"),
            "een handmatige poging herhaalt niet wat de lus al meldde",
        )
        assertTrue(
            Regex("""openstaandePaneelfouten\.(clear|delete)\(""").containsMatchIn(script).not(),
            "een geslaagde poging wist storingen die ze niet gecontroleerd heeft",
        )

        // Anders noemt het paneel zichzelf compleet terwijl er nog een storing openstaat. De tak
        // zelf, want de losse uitdrukking laat zowel een omgekeerde toets als één gedeelde tekst door.
        val bevestiging = functie("richtIn").substringAfter("const compleet =").substringBefore("inrichtPoging = 0;")

        assertTrue("openstaandePaneelfouten.size === 0" in bevestiging, "de bevestiging kijkt niet wat er openstaat")
        assertTrue("compleet ? 'goed' : 'let-op'" in bevestiging, "een halve inrichting leest als een geslaagde")
        assertTrue("het paneel is compleet" in bevestiging, "de bevestiging bij een complete inrichting ontbreekt")
        assertTrue("werkt niet" in bevestiging, "de bevestiging bij een halve inrichting ontbreekt")
        assertTrue(
            "if (inrichtPoging > 0 || metHand)" in functie("richtIn"),
            "een gewone start meldt ongevraagd dat de omgeving gelezen is",
        )

        // Anoniem melden maakt twee dode bedieningen tot één melding, en de tweede verdwijnt dan in
        // de ontdubbeling.
        assertTrue("'een keuzelijst'" !in script, "een ontbrekende keuzelijst wordt niet bij naam genoemd")

        // Een lijst die onbruikbaar terugkwam is geen ontbrekend element, maar wel een dode
        // bediening; zonder dit noemt de volgende geslaagde poging het paneel compleet.
        assertTrue(
            "registreerPaneelfout(" in functie("meldOnbruikbareLijst"),
            "een onbruikbare lijst telt niet mee als storing",
        )
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
    fun `een onleesbare keuzelijst zet zijn knop uit`() {
        assertTrue(
            "zetWachtOpLijst(knop, true)" in functie("meldLijstOnbekend"),
            "een knop boven een onleesbare lijst blijft indrukbaar",
        )
    }

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
     * op één plek af. Dit gaat over de actieknoppen — de variabele heet daar `knop`; de knop van het
     * inrichtingsblok (`inrichtingKnop`) heeft maar één eigenaar en valt er expliciet buiten.
     */
    @Test
    fun `alleen werkKnopBij zet een actieknop aan of uit`() {
        // `=(?!=)` en niet `=`: anders telt een toekomstige vergelijking `knop.disabled ===` mee en
        // faalt deze test op code die niets schrijft.
        val schrijft = Regex("""(?<![A-Za-z])knop\.disabled\s*=(?!=)""")

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

        assertTrue(
            Regex("""actieLoopt === 'ja' \|\| knop\.dataset\.wachtOpLijst === 'ja'""").containsMatchIn(afleiding),
            "werkKnopBij weegt de twee redenen niet als losse redenen; met && staat een wachtende knop aan",
        )

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
        assertTrue("clearTimeout(timer)" in uitlezen, "lees() laat zijn timer staan na een geslaagde uitlezing")

        // Op de fout zelf en met de status erbij: `signal.aborted` staat ook op true wanneer de timer
        // net ná een echte fout afgaat, en een status die tijdens het lezen van de body verloren gaat
        // is juist het enige aanknopingspunt.
        assertTrue("AbortError" in uitlezen, "lees() leidt een afgebroken uitlezing af uit het signal")
        assertTrue("status = respons.status" in uitlezen, "lees() houdt de HTTP-status niet vast")
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
    fun `een geplande poging wordt eerst gewist en wist zichzelf`() {
        // Zonder argument: `richtIn(true)` zou elke automatische poging als handwerk laten tellen,
        // waarna de wachttijd nooit oploopt. En de id moet weg zodra de timer afgaat, anders belooft
        // een mislukte handmatige poging een automatische die niet gepland staat.
        val plannen = functie("planInrichting")

        assertTrue("richtIn();" in plannen, "de geplande poging telt niet als automatische poging")
        assertTrue("inrichtTimer = null;" in plannen, "een afgegane timer laat zijn id staan")
        assertTrue("clearTimeout(inrichtTimer)" in plannen, "planInrichting wist de vorige timer niet")
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
        val blok = Regex("""<div[^>]*\sid="inrichting"[^>]*>""").find(paneel)?.value.orEmpty()

        assertTrue("hidden" in blok, "het foutblok van de inrichting staat bij het laden open")

        // Het blok verschijnt ná het laden; zonder deze rol hoort een schermlezer dat niet.
        assertTrue("role=\"status\"" in blok, "het foutblok kondigt zichzelf niet aan")
        assertTrue("aria-live" in blok, "het foutblok kondigt zichzelf niet aan")
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
        assertTrue("planInrichting(wacht)" in mislukt, "een mislukking plant nooit een nieuwe poging")

        // De voorwaarde zelf, want `planInrichting(wacht)` staat er ook nog als hij achter een vlag
        // hangt die altijd onwaar is — en dan komt er na een mislukking nooit meer een poging.
        assertTrue(
            "const opnieuwGepland = !metHand || inrichtTimer === null;" in mislukt,
            "de keuze om opnieuw te plannen staat er niet meer; een dode lus valt zo niet op",
        )
        val catchTak = inrichten.substringAfter("} catch")

        assertTrue("toonInrichtingsfout(" in catchTak, "de catch-tak meldt niets")
        assertTrue(
            "inrichtTimer === null ?" in catchTak,
            "de catch-tak belooft dat het paneel het opgeeft, ook als er nog een poging klaarstaat",
        )

        val geslaagd = inrichten.substringAfter("planInrichting(null);").substringBefore("} catch")

        assertTrue("toonInrichting(false)" in geslaagd, "na een geslaagde poging blijft het foutblok staan")
        assertTrue("planInrichting(null)" in inrichten, "een geslaagde poging laat de geplande poging staan")
        assertTrue("toonMelding(" in geslaagd, "een herstel na een storing wordt niet gemeld")

        val fouttekst = functie("toonInrichtingsfout")

        assertTrue("inrichtingTekst.textContent = tekst;" in fouttekst, "de tekst wordt niet geschreven")
        assertTrue("toonInrichting(true)" in fouttekst, "de tekst komt in een blok dat verborgen blijft")
        assertTrue("toonMelding(tekst" in fouttekst, "zonder blok gaat de concrete oorzaak verloren")
        assertTrue("'melding melding--fout'" in fouttekst, "een storing krijgt de opmaak van een gewone melding")
        assertTrue("registreerPaneelfout(" in fouttekst, "een blok zonder tekstregel telt niet mee als storing")

        // Eén melding en niet twee: de balk toont er maar één, dus de tweede wist de eerste.
        assertEquals(
            1,
            Regex("""toonMelding\(""").findAll(fouttekst).count(),
            "twee meldingen achter elkaar laten er maar één over",
        )
        assertTrue(
            "inrichtingKnop.disabled = bezigMetInrichten" in functie("zetInrichtenBezig"),
            "de knop volgt de poging niet; hij kan zo permanent uit blijven staan",
        )
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

        // De automatische keten plant zichzelf opnieuw wanneer hij op een lopende poging botst;
        // zonder die regel dooft de lus uit zodra dat één keer gebeurt.
        assertTrue(
            "else planInrichting(volgendeWachttijd());" in inrichten,
            "een overgeslagen automatische poging plant geen opvolger",
        )
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
     * De ingedrukte knop gaat op disabled en verliest zijn focus naar `<body>`. Zonder teruggave
     * begint toetsenbordnavigatie weer bovenaan de pagina; zonder de voorwaarden eromheen trekt een
     * automatische poging de focus weg bij wie ergens anders bezig is.
     */
    @Test
    fun `de knop krijgt zijn focus terug, en alleen als hij die had`() {
        val afsluiting = functie("richtIn").substringAfter("} finally {")

        assertTrue("inrichtingKnop.focus()" in afsluiting, "de focus blijft op body staan")
        assertTrue("metHand &&" in afsluiting, "een automatische poging kaapt de focus")
        assertTrue("document.activeElement === document.body" in afsluiting, "de focus wordt weggetrokken")
        assertTrue("offsetParent !== null" in afsluiting, "de focus kan op een onzichtbare knop landen")
    }

    /**
     * Het inrichtingsblok staat ín het paneel, en een ingeklapt paneel is `display: none`. Zonder
     * merkteken op de klap-knop is een half ingericht paneel dan nergens aan te zien.
     */
    @Test
    fun `een ingeklapt paneel laat zien dat het niet compleet is`() {
        assertTrue(
            "markeerKlap(zichtbaar || openstaandePaneelfouten.size > 0)" in functie("toonInrichting"),
            "het foutblok wist het merkteken terwijl er nog een opmaakfout staat",
        )
        assertTrue("dataset.letOp = String(letOp)" in functie("markeerKlap"), "markeerKlap zet geen merkteken")

        val klapbijschrift = functie("werkKlapBij")

        assertTrue("dataset.letOp === 'true'" in klapbijschrift, "het merkteken overleeft een in- of uitklap niet")
        assertTrue("letOp ?" in klapbijschrift, "het bijschrift zegt niet dat het paneel niet compleet is")
        assertTrue(
            Regex("""#klap\[data-let-op="true"]::after""").containsMatchIn(stijl),
            "de opmaak toont geen merkteken op de klap-knop",
        )
    }

    /** Een knop die op zijn lijst wacht is niet bezig; de progress-cursor zou zeggen van wel. */
    @Test
    fun `een wachtende knop draagt niet de cursor van een lopende actie`() {
        assertTrue(
            Regex("""\.knop\[data-wacht-op-lijst="ja"]:disabled \{[^}]*not-allowed""").containsMatchIn(stijl),
            "een wachtende knop krijgt de bezig-cursor",
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

        // Vóór de éérste bedradingsregel die een element dereferencet: die kan zelf omvallen, en dan
        // was het vangnet nog niet geregistreerd en stopt het script zonder een woord. Ankeren op de
        // laatste regel van de bedrading zou de oude, te late plaatsing goedkeuren.
        val eersteBedrading = script.indexOf("document.querySelector('[role=\"tablist\"]')")

        assertTrue(eersteBedrading > 0, "de bedrading niet gevonden; klopt dit anker nog?")
        assertTrue(
            script.indexOf("addEventListener('error'") < eersteBedrading,
            "het vangnet staat ná de bedrading die het zou moeten dekken",
        )
        assertTrue(
            script.indexOf("addEventListener('unhandledrejection'") < eersteBedrading,
            "de listener voor afgewezen promises staat ná de bedrading die het zou moeten dekken",
        )

        // De meldingsbalk is vijf elementen; valt `toonMelding` over één ervan, dan sleept hij het
        // vangnet mee dat hem net aanriep.
        val tonen = functie("toonMelding")
        val onbewaakt = listOf("melding", "meldingTekst", "meldingLetOp", "meldingRuw", "meldingJson")
            .filterNot { "!$it" in tonen.substringBefore("return;") }

        assertEquals(emptyList<String>(), onbewaakt, "toonMelding kan over dit element struikelen")

        // De poll draait elke paar seconden; zonder ontdubbeling schrijft één storing daarin steeds
        // opnieuw over de uitkomst van de laatste actie heen.
        val vangnet = functie("meldVangnet")

        assertTrue("toonMelding(" in vangnet, "het vangnet komt niet verder dan de console")
        assertTrue("boodschap === laatsteVangnetfout" in vangnet, "het vangnet ontdubbelt niet")
        assertTrue("laatsteVangnetfout = boodschap;" in vangnet, "de ontdubbeling wapent zich nooit")
        assertTrue("+ boodschap" in vangnet, "de melding noemt de fout niet")
        assertTrue(
            vangnet.indexOf("toonMelding(") < vangnet.indexOf("laatsteVangnetfout = boodschap;"),
            "de vlag wordt gezet vóór toonMelding, die hem juist wist; de ontdubbeling grijpt dan nooit",
        )
        assertTrue(
            "laatsteVangnetfout = null;" in functie("toonMelding"),
            "een andere melding maakt de weg niet vrij; dezelfde fout blijft daarna stil",
        )
    }

    // ------------------------------------------------------------ bedrading opmaak en script

    /**
     * Het merkteken op de knop is de helft van het antwoord: de melding zegt wát er gebeurde, dit
     * zegt wélke knop het deed. Het hangt aan drie dingen die los van elkaar kunnen breken — de span
     * die het script aan elke knop hangt, het dataset-attribuut dat de uitkomst draagt, en de
     * CSS-regels die daarop tekenen.
     */
    @Test
    fun `het merkteken op een knop is compleet bedraad`() {
        val klasse = Regex("""merk\.className = '([^']+)'""").find(script)?.groupValues?.get(1)

        assertTrue(klasse != null, "het script hangt geen merkteken aan de knoppen")
        assertTrue(".$klasse" in stijl, "de stylesheet kent de klasse van het merkteken niet")
        assertTrue(
            "querySelectorAll('button[data-pad]')" in script,
            "het merkteken wordt niet meer aan de actieknoppen gehangen",
        )
        assertTrue("knop.dataset.uitkomst = uitkomst;" in functie("zetUitkomst"), "de uitkomst wordt niet gezet")

        val uitkomsten = Regex("""zetUitkomst\(knop, '([^']+)'\)""").findAll(script)
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(uitkomsten.isNotEmpty(), "geen enkele uitkomst gevonden; klopt dit patroon nog?")

        val zonderRegel = uitkomsten.filterNot { """data-uitkomst="$it"""" in stijl }

        assertEquals(emptyList<String>(), zonderRegel, "uitkomst die de stylesheet niet tekent")
    }

    /** Zonder deze aanroep richt het paneel zich nooit in en blijft elke keuzelijst leeg. */
    @Test
    fun `het paneel richt zichzelf in bij het laden`() {
        assertTrue("richtIn(false);" in script, "het paneel start het inrichten niet")
    }

    /**
     * `LOSSE_ACTIES[naam]()` gooit een TypeError op een naam die het script niet kent. Die vliegt
     * uit de listener: de knop doet niets, en niets zegt waarom. Het script vangt dat af met een
     * eigen melding — bewust geen opmaakfout, want de opmaak draagt die naam juist wél — maar de
     * naam hoort natuurlijk gewoon te bestaan.
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

        // En zegt het ook: zonder deze melding doet zo'n knop weer niets zonder uitleg.
        val bedrading = script.substringAfter("Object.hasOwn(LOSSE_ACTIES")

        assertTrue(
            "+ knop.dataset.actie" in bedrading.substringBefore("}"),
            "de melding noemt niet wélke actie het paneel niet kent",
        )
        assertTrue(
            "!Object.hasOwn(LOSSE_ACTIES" in script,
            "de toets staat er, maar niet als guard; een bekende actie wordt dan als onbekend gemeld",
        )
        assertTrue(
            "registreerPaneelfout(" in bedrading.substringBefore("}"),
            "een knop die aan niets hangt telt niet mee als storing",
        )
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

        // Ook de selectors die niet op id lopen: `querySelector` geeft net zo goed stil null terug,
        // en een tab-lijst of samenvatting die niet meer bestaat sloopt de bedrading bij het laden.
        val uitSelectors = Regex("""querySelector(?:All)?\('[^']*\[([\w-]+)="([^"]+)"]""")
            .findAll(script)
            .map { it.groupValues[1] + "=\"" + it.groupValues[2] + "\"" }
            .toSet()

        assertTrue(uitSelectors.isNotEmpty(), "geen enkele attribuut-selector gevonden; klopt dit patroon nog?")
        assertEquals(
            emptySet<String>(),
            uitSelectors.filterNot { it in paneel }.toSet(),
            "het script zoekt op een attribuut dat de opmaak niet draagt",
        )
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
