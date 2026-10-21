package nl.rijksoverheid.moz.fbs.berichtensessiecache

import io.smallrye.mutiny.Multi
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.SessieGebeurtenis
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.OpruimUitkomst
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.Opruiming
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieEinde
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieStart
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.util.UUID

/**
 * Facade van de berichtensessiecache: de enige publieke ingang van deze library.
 * Consumers injecteren dit CDI-bean; alle implementatie (Redis-cache, magazijn-
 * aggregatie, Profiel-resolver) is `internal` en alle sessiestaat leeft in Redis —
 * de facade-impl is stateless zodat meerdere pods dezelfde sessies kunnen bedienen.
 *
 * Foutsemantiek: de synchrone lees-/schrijfmethoden gooien een [SessiecacheException]
 * (gesloten hiërarchie) zodat de consumer elk geval naar zijn eigen transport vertaalt;
 * een nieuw foutscenario dwingt daar een bouwfout af. Zie [SessiecacheException] voor de
 * afzonderlijke foutgevallen.
 *
 * De streams [ophalen] en [volg] hebben een eigen asynchroon foutkanaal (`Multi`-failure) voor
 * wat er ná het openen misgaat, en vallen daarvoor buiten deze hiërarchie.
 */
interface Sessiecache {

    companion object {
        /** Grootste pagina die [lijst] en [zoek] leveren; een grotere vraag wordt hierop afgekapt. */
        const val MAX_PAGINA_GROOTTE: Int = 200

        /**
         * Mapwaarde die een bericht uit zijn map haalt. `null` betekent in een merge-patch al "niet
         * wijzigen", dus wissen heeft een eigen waarde nodig; het magazijn gebruikt dezelfde.
         */
        const val MAP_WISSEN = ""
    }

    /**
     * Berichtenlijst voor [ontvanger], gepagineerd, optioneel gefilterd op
     * [afzender] en/of [map]. Vereist een afgeronde ophaling (zie foutsemantiek).
     * De pagina draagt altijd [BerichtenPagina.volledigheid] van de laatste ronde,
     * ook bij [zoek].
     * `pagina` default 0; `paginaGrootte` default 20, gecapt op [MAX_PAGINA_GROOTTE].
     */
    fun lijst(
        ontvanger: Identificatienummer,
        pagina: Int? = null,
        paginaGrootte: Int? = null,
        afzender: String? = null,
        map: String? = null,
    ): BerichtenPagina

    /**
     * Volledig-tekst zoeken (RediSearch) in het onderwerp van de berichten van [ontvanger].
     * Zelfde paginering, filters en gereed-vereiste als [lijst].
     */
    fun zoek(
        ontvanger: Identificatienummer,
        q: String,
        pagina: Int? = null,
        paginaGrootte: Int? = null,
        afzender: String? = null,
        map: String? = null,
    ): BerichtenPagina

    /**
     * Alle gecachete gegevens van één bericht (kopgegevens en bijlage-handles), of
     * `null` als het niet bestaat of niet van [ontvanger] is. Vereist een afgeronde
     * ophaling. De berichttekst zit hier niet bij; zie [Bericht].
     *
     * Gooit [SessiecacheException.BerichtVerwijderd] wanneer [ontvanger] dit bericht binnen deze
     * sessie zélf verwijderde. Het bericht van een ánder levert `null`, gelijk aan een onbekend
     * bericht: het verschil tussen "bestaat niet" en "bestaat wel maar is niet van jou" hoort
     * hier niet zichtbaar te worden.
     *
     * Een organisatie deelt haar berichtnummers zelf uit, dus een nummer is alleen uniek binnen het
     * magazijn dat het uitdeelde; daarom hoort [magazijnId] bij de vraag. Dat geldt ook voor
     * [werkBerichtBij] en [verwijder].
     */
    fun bericht(ontvanger: Identificatienummer, magazijnId: String, berichtId: UUID): Bericht?

    /**
     * Merge-PATCH op leesstatus en/of map. Minimaal één van beide moet gezet zijn
     * (anders 400). `map` = [MAP_WISSEN] haalt het bericht uit zijn map, terug naar
     * Postvak IN. Retourneert het bijgewerkte bericht, of `null` als het bericht
     * niet bestaat of niet van [ontvanger] is.
     */
    fun werkBerichtBij(
        ontvanger: Identificatienummer,
        magazijnId: String,
        berichtId: UUID,
        status: Leesstatus?,
        map: String?,
    ): Bericht?

    /**
     * Idempotente cache-invalidate: "niet in cache" is geen fout (TTL kan verlopen
     * zijn, of een andere instance heeft de dual-write-invalidate al uitgevoerd).
     */
    fun verwijder(ontvanger: Identificatienummer, magazijnId: String, berichtId: UUID)

    /**
     * Orkestreert het ophalen van berichten uit de voor [ontvanger] relevante
     * magazijnen en vult de cache; retourneert een SSE-compatible event-stream
     * met per-magazijn voortgang en een afsluitend GEREED/FOUT-event. Gooit 409
     * wanneer er al een ophaling loopt voor deze ontvanger (atomaire Redis-lock).
     * Eindigt de laatste sessie terwijl de ronde loopt, dan wist de ronde wat ze
     * schreef en eindigt de stream zonder afsluitend event.
     */
    fun ophalen(ontvanger: Identificatienummer): Multi<MagazijnEvent>

    /**
     * Schrijft één bericht bij in een bestaande, actieve sessie (aanmeld-pad).
     * Gooit 400 wanneer `bericht.ontvanger` niet overeenkomt met [ontvanger] of
     * wanneer het bericht de defensieve limieten overschrijdt; 404 wanneer er
     * geen actieve sessie is voor deze ontvanger, ook als de laatste sessie eindigde
     * terwijl het bericht geschreven werd — het is dan weer gewist.
     */
    fun schrijfBericht(ontvanger: Identificatienummer, bericht: Bericht): Bericht

    /**
     * Volgt de inlogsessie [sessieId] van [ontvanger] terwijl de berichtenbox openstaat: elk bericht
     * dat via [schrijfBericht] binnenkomt — op welke pod ook — komt als gebeurtenis door, en een
     * periodieke hartslag raakt de sessie (zie [raakSessie]) en houdt hem zo in leven. Eindigt de
     * sessie, uitgelogd of verlopen, dan meldt de stream [SessieGebeurtenis.SessieVerlopen] en sluit.
     * Zie [SessieGebeurtenis] voor het verloop.
     *
     * Synchroon en vóór de stream: [SessiecacheException.SessieOngeldig] als [sessieId] geen lopende
     * sessie van [ontvanger] is, daarna net als [lijst] de eis van een afgeronde ophaling met
     * dezelfde [SessiecacheException]s. Ontvangt deze pod geen aanmeldingen, dan is dat eveneens vóór
     * de stream een [SessiecacheException.Onbereikbaar]. Een storing daarna, ook bij het raken van de
     * sessie, beëindigt de stream met een fout en niet met [SessieGebeurtenis.SessieVerlopen]; de
     * afnemer verbindt dan opnieuw.
     *
     * De stream eindigt ook uit zichzelf na `berichtensessiecache.volg-max-duur`, zonder
     * [SessieGebeurtenis.SessieVerlopen]: de sessie loopt dan nog, en de afnemer verbindt opnieuw.
     */
    fun volg(ontvanger: Identificatienummer, sessieId: String): Multi<SessieGebeurtenis>

    /**
     * Start een inlogsessie voor [ontvanger]. Loopt er voor hem nog geen andere sessie, dan worden
     * eerst de gegevens van een eerdere, verlopen sessie gewist: een sessie begint met een vers
     * beeld, en [SessieStart.restenOpgeruimd] meldt of er iets te wissen viel.
     *
     * Gooit [SessiecacheException.TeVeelSessies] wanneer [ontvanger] al het maximum aantal lopende
     * sessies heeft (`berichtensessiecache.sessie.max-per-ontvanger`); er wordt geen bestaande
     * sessie verdrongen, want dat logt een gebruiker ongemerkt uit.
     */
    fun startSessie(ontvanger: Identificatienummer): SessieStart

    /**
     * Controleert dat [sessieId] een lopende sessie van [ontvanger] is en verlengt hem met de
     * inactiviteitsduur, nooit voorbij de maximale duur vanaf de start. Gooit
     * [SessiecacheException.SessieOngeldig] voor een onbekende, verlopen of andermans sessie — die
     * drie zijn voor de aanroeper bewust niet te onderscheiden.
     */
    fun raakSessie(sessieId: String, ontvanger: Identificatienummer)

    /**
     * Beëindigt de sessie [sessieId] van [ontvanger]. Loopt er daarna geen sessie meer voor hem,
     * dan worden zijn gegevens gewist ([SessieEinde.OPGERUIMD]) — ook bij een onbekende of al
     * verlopen sessie, als er nog gegevens stonden: elk wissen hoort gemeld te worden.
     * Idempotent: een onbekende of verlopen sessie is geen fout; zonder iets te wissen is de
     * uitkomst [SessieEinde.ONBEKEND].
     */
    fun beeindigSessie(sessieId: String, ontvanger: Identificatienummer): SessieEinde

    /**
     * Ruimt hoogstens [max] ontvangers op wier laatste sessie verlopen is, en meldt per ontvanger
     * het resultaat; elk wissen hoort in het logboek. Bedoeld voor een periodieke taak, en veilig om
     * op meerdere pods tegelijk te draaien: per ontvanger beslist één atomaire stap, dus een
     * opruiming wordt precies één keer gemeld. Wie in de tussentijd een sessie startte of raakte,
     * blijft staan en verschijnt niet in het resultaat.
     *
     * Per ontvanger is de uitkomst [OpruimUitkomst.GEWIST], [OpruimUitkomst.MISLUKT] (de ontvanger
     * blijft in de index; een volgende ronde probeert opnieuw) of [OpruimUitkomst.ONBEVESTIGD] (geen antwoord
     * binnen de wachttijd: de gegevens zijn mogelijk gewist, en anders vangt de Redis-TTL ze op). Een
     * fout bij één ontvanger breekt de ronde niet af. Gooit [SessiecacheException.Onbereikbaar] als
     * de verlopen sessies niet op te vragen zijn, en [SessiecacheException.OngeldigeInvoer] voor een
     * [max] kleiner dan 1.
     */
    fun ruimVerlopenSessiesOp(max: Int = 100): List<Opruiming>
}
