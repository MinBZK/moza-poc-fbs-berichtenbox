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

        // En elk een eigen naam: twee velden die "het aantal" heten laten de melding weer in het
        // midden, en dat is precies waarvoor dit attribuut bestaat.
        val namen = Regex("""data-veldnaam="([^"]+)"""").findAll(paneel).map { it.groupValues[1] }.toList()

        assertEquals(namen.size, namen.toSet().size, "twee velden delen dezelfde data-veldnaam")
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
        assertTrue(
            "typeof veld.checkValidity !== 'function'" in invullen,
            "de toets op een element dat geen formulierveld is, is omgedraaid of weg",
        )

        // Alles vóór de eerste regel van de echte actie: precies de uitgangen die zonder antwoord
        // kunnen eindigen.
        val voorbereiding = totAan(functie("voerUit"), "bezig += 1;")
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
     *
     * En daarnaast: wat er openstaat hoort ná een geslaagde poging nog te kloppen, want daar hangen
     * het merkteken op de klap-knop en de bevestiging aan.
     */
    @Test
    fun `wat openstaat wordt bijgehouden, en een druk op een knop wordt niet ontdubbeld`() {
        val melden = functie("meldOpmaakfout")

        // Binnen de vlag-tak en niet ergens in de functie: zonder die afbakening blijft deze test
        // groen als de `if (uitDeLus)` eromheen verdwijnt, en dan wordt ook een melding uit een klik
        // ontdubbeld — precies de stilte waar deze wijziging over gaat.
        val ontdubbeling = tak(melden, "registreerPaneelfout(wat);")

        assertTrue(
            "if (uitDeLus && ontdubbeldInLus.has(wat)) return;" in melden,
            "de ontdubbeling geldt ook voor een druk op een knop",
        )
        assertTrue("if (uitDeLus) ontdubbeldInLus.add(wat);" in ontdubbeling, "een klik-melding wordt ontdubbeld")
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
        assertTrue(
            "if (metHand) ontdubbeldInLus.clear();" in functie("richtIn"),
            "een handmatige poging herhaalt niet wat de lus al meldde",
        )
        // Een ontbrekend element in de opmaak gaat niet over van een uitlezing die dat element niet
        // bekijkt; een onbruikbare lijst juist wél, want die wordt elke poging opnieuw vastgesteld.
        assertTrue(
            Regex("""openstaandePaneelfouten\.(clear|delete)\(""").containsMatchIn(script).not(),
            "een geslaagde poging wist storingen die ze niet gecontroleerd heeft",
        )
        assertTrue(
            "onbruikbareLijsten.clear();" in functie("pasOmgevingToe"),
            "een lijst die weer klopt blijft de rest van de sessie als storing gelden",
        )
        assertTrue(
            "if (uitlezingGelukt)" in functie("meldOnbruikbareLijst"),
            "een console die even weg was telt als een kapotte lijst",
        )

        val openstaand = functie("ietsOpenstaand")

        assertTrue("openstaandePaneelfouten.size" in openstaand, "de opmaakfouten tellen niet mee")
        assertTrue("onbruikbareLijsten.size" in openstaand, "de onbruikbare lijsten tellen niet mee")

        // Anders noemt het paneel zichzelf compleet terwijl er nog een storing openstaat. De tak
        // zelf, want de losse uitdrukking laat zowel een omgekeerde toets als één gedeelde tekst door.
        val bevestiging = tak(functie("richtIn"), "const compleet =", "inrichtPoging = 0;")

        assertTrue("!ietsOpenstaand()" in bevestiging, "de bevestiging kijkt niet wat er openstaat")
        assertTrue("watOpenstaat()" in bevestiging, "de melding noemt niet wát er dan niet werkt")
        assertTrue("compleet ? 'goed' : 'let-op'" in bevestiging, "een halve inrichting leest als een geslaagde")
        assertTrue("het paneel is compleet" in bevestiging, "de bevestiging bij een complete inrichting ontbreekt")
        assertTrue("werkt niet" in bevestiging, "de bevestiging bij een halve inrichting ontbreekt")
        assertTrue(
            "(inrichtPoging > 0 || metHand)" in functie("richtIn"),
            "een gewone start meldt ongevraagd dat de omgeving gelezen is",
        )

        // Anoniem melden maakt twee dode bedieningen tot één melding, en de tweede verdwijnt dan in
        // de ontdubbeling.
        assertTrue("'een keuzelijst'" !in script, "een ontbrekende keuzelijst wordt niet bij naam genoemd")

        // Een lijst die onbruikbaar terugkwam is geen ontbrekend element, maar wel een dode
        // bediening; zonder dit noemt de volgende geslaagde poging het paneel compleet.
        assertTrue(
            "onbruikbareLijsten.add(" in functie("meldOnbruikbareLijst"),
            "een onbruikbare lijst telt niet mee als storing",
        )

        // Élke aanroep, ook de terugval in de catch: zonder de vlag telt die lijst niet mee en noemt
        // de volgende geslaagde poging het paneel compleet.
        val zonderVlag = Regex("""vul(?:Bericht)?Personas\([^)]*\)""")
            .findAll(functie("pasOmgevingToe"))
            .map { it.value }
            .filterNot { "gelezen" in it }
            .toList()

        assertEquals(emptyList<String>(), zonderVlag, "aanroep zonder uitlezingGelukt; die telt niet mee")
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

    /** Zonder persona kan de knop niets versturen; levend ogen en niets doen is dan het ergste. */
    @Test
    fun `een onleesbare keuzelijst zet zijn knop uit`() {
        assertTrue(
            "zetWachtOpLijst(knop, true)" in functie("meldLijstOnbekend"),
            "een knop boven een onleesbare lijst blijft indrukbaar",
        )
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
        assertTrue("keuze.disabled = true" in functie("meldLijstOnbekend"), "de onleesbare lijst blijft bedienbaar")
        assertTrue(
            "onbekend.value = '';" in functie("meldLijstOnbekend"),
            "de optie bij een onleesbare lijst draagt haar tekst als waarde",
        )

        // In de lege-tak zelf, zodat een latere vrijgave elders in de functie deze assertie niet
        // ongemerkt overneemt en de knop boven een lege lijst weer aan komt te staan.
        assertTrue(
            "zetWachtOpLijst(knop, true)" in tak(vullen, "if (!opties.length) {"),
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

        // Op de fetch zelf: het woord "signal" staat ook in het commentaar eronder, en dat breekt
        // niets af.
        assertTrue(
            Regex("""fetch\([^)]*signal:""").containsMatchIn(uitlezen),
            "lees() geeft zijn fetch geen signal mee en breekt dus nooit af",
        )
        assertTrue("clearTimeout(timer)" in uitlezen, "lees() laat zijn timer staan na een geslaagde uitlezing")
        assertTrue("staak.abort()" in uitlezen, "de timer breekt de uitlezing niet af")

        // Een status die tijdens het lezen van de body verloren gaat, is juist het enige
        // aanknopingspunt bij een 401 of een 503.
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
        // Anker op de zin die over deze reeks gaat, niet op de openingszin van de alinea: die is
        // proza en verandert eerder dan het onderwerp.
        val alinea = tak(PaneelBestanden.leesmij(), "kent een timeout van", "\n\n")

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
        assertTrue("wacht === null ? null :" in plannen, "planInrichting(null) plant een poging in plaats van te wissen")
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
        val mislukt = tak(inrichten, "if (!gelukt) {", "return;")

        assertTrue("toonInrichtingsfout(" in mislukt, "een mislukte uitlezing laat de tekst onveranderd")
        assertTrue("Math.round(wacht / 1000)" in mislukt, "de wachttijd staat in milliseconden in de melding")
        assertTrue("if (opnieuwGepland) planInrichting(wacht);" in mislukt, "de planning hangt niet aan de vlag")

        // De voorwaarde zelf, want `planInrichting(wacht)` staat er ook nog als hij achter een vlag
        // hangt die altijd onwaar is — en dan komt er na een mislukking nooit meer een poging.
        assertTrue(
            "const opnieuwGepland = !metHand || inrichtTimer === null;" in mislukt,
            "de keuze om opnieuw te plannen staat er niet meer; een dode lus valt zo niet op",
        )

        val catchTak = tak(inrichten, "} catch")

        assertTrue("toonInrichtingsfout(" in catchTak, "de catch-tak meldt niets")
        assertTrue(
            "inrichtTimer === null ?" in catchTak,
            "de catch-tak belooft dat het paneel het opgeeft, ook als er nog een poging klaarstaat",
        )

        val geslaagd = tak(inrichten, "planInrichting(null);", "} catch")

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
        assertTrue(
            "if (!bezigMetInrichten || !inrichtingTekst) return;" in functie("zetInrichtenBezig"),
            "de bezig-tekst overschrijft de fouttekst na afloop",
        )
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
        assertTrue("inrichtLoopt = true;" in totAan(inrichten, "try {"), "de vlag gaat niet aan")
        assertTrue(
            "toonMelding(" in tak(inrichten, "if (inrichtLoopt) {", "return;"),
            "een afgewezen tweede poging zwijgt",
        )

        // De automatische keten plant zichzelf opnieuw wanneer hij op een lopende poging botst;
        // zonder die regel dooft de lus uit zodra dat één keer gebeurt.
        assertTrue(
            "else planInrichting(volgendeWachttijd());" in inrichten,
            "een overgeslagen automatische poging plant geen opvolger",
        )
        assertTrue(
            "inrichtLoopt = false" in tak(inrichten, "} finally {"),
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
        val afsluiting = tak(functie("richtIn"), "} finally {")

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
            "else registreerPaneelfout(" in functie("toonInrichting"),
            "een ontbrekend inrichtingsblok telt niet mee als storing",
        )
        assertTrue(
            "markeerKlap(zichtbaar || ietsOpenstaand())" in functie("toonInrichting"),
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
        val guard = totAan(tonen, "return;")
        val onbewaakt = listOf("melding", "meldingTekst", "meldingLetOp", "meldingRuw", "meldingJson")
            .filterNot { "!$it" in guard }

        assertEquals(emptyList<String>(), onbewaakt, "toonMelding kan over dit element struikelen")

        // Met `&&` gooit een half aanwezige balk alsnog, en sleept het vangnet mee dat hem aanriep.
        assertTrue("||" in guard, "de guard eist alle vijf de elementen tegelijk in plaats van elk apart")

        // De poll draait elke paar seconden; zonder ontdubbeling schrijft één storing daarin steeds
        // opnieuw over de uitkomst van de laatste actie heen.
        val vangnet = functie("meldVangnet")

        assertTrue("toonMelding(" in vangnet, "het vangnet komt niet verder dan de console")
        assertTrue(
            "if (boodschap === laatsteVangnetfout) return;" in vangnet,
            "het vangnet ontdubbelt niet; de poll schrijft dezelfde fout elke ronde opnieuw",
        )
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

    /**
     * "Berichtenbox verversen" en "Nu bijwerken" roepen geen adres aan en krijgen dus geen merkteken
     * — dat hangt alleen aan `button[data-pad]`. De meldingsbalk is voor hen het enige kanaal, ook in
     * de gevallen waarin ze niets te doen hebben: een verborgen berichtenbox, of een actie die loopt.
     */
    @Test
    fun `ook een knop zonder adres antwoordt als hij niets te doen heeft`() {
        val verversen = functie("verversBox")

        assertTrue("box.hidden" in verversen, "de toets op een verborgen frame is weg")
        assertTrue("toonMelding(" in tak(verversen, "box.hidden", "return;"), "een verborgen frame zwijgt")

        val toestand = functie("verversToestand")

        assertTrue(
            "if (metHand) toonMelding(" in tak(toestand, "if (bezig > 0)", "const beurt"),
            "een druk tijdens een lopende actie levert geen melding op",
        )
        assertTrue(
            "toonMelding(" in tak(toestand, "toonMagazijnen(veel);"),
            "een geslaagde bijwerking levert geen melding op",
        )
        assertTrue(
            "'ververs-toestand': () => verversToestand(true)," in script,
            "de knop is niet als handmatige bijwerking bedraad",
        )
    }

    /**
     * Een knop met een `data-samenvatting` die het script niet kent, of een formatter die niets
     * oplevert, betekent dat het paneel het antwoord niet begrijpt. Groen "Gelukt" laat een keten die
     * iets anders teruggeeft er dan gezond uitzien.
     */
    @Test
    fun `een antwoord dat het paneel niet begrijpt leest niet als geslaagd`() {
        val samenvatten = functie("samenvatting")

        assertTrue("soort: 'goed' }" !in samenvatten, "een onbekende samenvatting levert een groene melding op")
        // Drie: een onbekende samenvatting, een formatter die niets oplevert, en een formatter die
        // gooit. Alle drie betekenen "ik begrijp dit antwoord niet".
        assertEquals(
            3,
            Regex("""soort: 'let-op'""").findAll(samenvatten).count(),
            "een antwoord dat het paneel niet begrijpt leest als een geslaagde actie",
        )
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

        assertTrue("Object.hasOwn(LOSSE_ACTIES" in script, "een onbekende actie kan een geërfde functie oppikken")

        // En zegt het ook: zonder deze melding doet zo'n knop weer niets zonder uitleg.
        val bedrading = tak(script, "Object.hasOwn(LOSSE_ACTIES", "LOSSE_ACTIES[knop.dataset.actie]();")

        assertTrue("toonMelding(" in bedrading, "een onbekende actie levert geen melding op")
        assertTrue(
            Regex("""toonMelding\([^;]*\+ knop\.dataset\.actie""").containsMatchIn(bedrading),
            "de melding noemt niet wélke actie het paneel niet kent",
        )
        assertTrue(
            "!Object.hasOwn(LOSSE_ACTIES" in script,
            "de toets staat er, maar niet als guard; een bekende actie wordt dan als onbekend gemeld",
        )
        assertTrue(
            "registreerPaneelfout(" in bedrading,
            "een knop die aan niets hangt telt niet mee als storing",
        )
    }

    /**
     * De opzoekingen in dit script geven stil `null` bij een hernoemde id, en de meeste gebruikers
     * ervan dereferencen zonder toets: de eerste poll of de eerste klik loopt dan stuk op een
     * TypeError. Het vangnet meldt dat als "onverwachte fout", en dat vertelt de bediener niet wélk
     * element weg is — deze test wel.
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

    /**
     * Het stuk tussen twee ankers, met een assertie op elk anker.
     *
     * `substringAfter` geeft bij een ontbrekende scheiding de héle bron terug: een assertie over zo'n
     * stuk wordt dan stil waar in plaats van rood, en bewaakt vanaf dat moment niets meer. Elk anker
     * hier is brontekst uit `bediening.js`, dus een refactor die het hernoemt hoort deze test te laten
     * struikelen — niet stiekem te verruimen.
     */
    private fun tak(bron: String, van: String, tot: String? = null): String {
        assertTrue(van in bron, "het anker \"$van\" bestaat niet meer; klopt deze test nog?")

        val na = bron.substringAfter(van)

        if (tot == null) return na

        assertTrue(tot in na, "het anker \"$tot\" bestaat niet meer; klopt deze test nog?")

        return na.substringBefore(tot)
    }

    /** Alles tot aan het anker, met dezelfde assertie erop. */
    private fun totAan(bron: String, tot: String): String {
        assertTrue(tot in bron, "het anker \"$tot\" bestaat niet meer; klopt deze test nog?")

        return bron.substringBefore(tot)
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
