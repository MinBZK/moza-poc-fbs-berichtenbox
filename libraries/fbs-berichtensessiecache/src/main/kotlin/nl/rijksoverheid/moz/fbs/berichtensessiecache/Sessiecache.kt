package nl.rijksoverheid.moz.fbs.berichtensessiecache

import io.smallrye.mutiny.Multi
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.SessieGebeurtenis
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

        /**
         * De vorm van elk sessie-id dat [startSessie] uitgeeft: 128 bits, base64url zonder opvulling.
         * Gelijk aan `components/parameters/SessieHeader` in de spec van de uitvraag. Wat er niet aan
         * voldoet, kan geen sessie zijn en hoeft Redis niet te zien.
         */
        val SESSIE_ID_VORM: Regex = Regex("^[A-Za-z0-9_-]{22}$")
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
     */
    fun ophalen(ontvanger: Identificatienummer): Multi<MagazijnEvent>

    /**
     * Schrijft één bericht bij in de al opgehaalde lijst van [ontvanger] (aanmeld-pad).
     * Gooit 400 wanneer `bericht.ontvanger` niet overeenkomt met [ontvanger] of
     * wanneer het bericht de defensieve limieten overschrijdt; 404 wanneer er
     * geen opgehaalde lijst is voor deze ontvanger.
     */
    fun schrijfBericht(ontvanger: Identificatienummer, bericht: Bericht): Bericht

    /**
     * Volgt de inlogsessie [sessieId] van [ontvanger] terwijl de berichtenbox openstaat: elk bericht
     * dat via [schrijfBericht] binnenkomt — op welke pod ook — komt als gebeurtenis door, en een
     * periodieke hartslag controleert of de sessie nog loopt (zie [leesSessie]) en verlengt zolang de
     * gegevens van de ontvanger. Eindigt de sessie, dan meldt de stream [SessieGebeurtenis.SessieVerlopen]
     * en sluit. Zie [SessieGebeurtenis] voor het verloop.
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
     * Start een inlogsessie voor [ontvanger]. Een ontvanger kan meerdere sessies tegelijk hebben.
     *
     * De sessie verloopt niet uit zichzelf: ze loopt tot [beeindigSessie]. Wordt ze nooit beëindigd,
     * dan verdwijnt ze met de TTL op de sessie-administratie (`berichtensessiecache.sessie.max-duur`
     * na de laatste start van de ontvanger).
     */
    fun startSessie(ontvanger: Identificatienummer): SessieStart

    /**
     * Controleert dat [sessieId] een lopende sessie van [ontvanger] is. Gooit
     * [SessiecacheException.SessieOngeldig] voor een onbekende, verlopen of andermans sessie — die
     * drie zijn voor de aanroeper bewust niet te onderscheiden.
     */
    fun leesSessie(sessieId: String, ontvanger: Identificatienummer)

    /**
     * Beëindigt de sessie [sessieId] van [ontvanger]; zijn andere sessies lopen door.
     * Idempotent: een onbekende of verlopen sessie is geen fout, de uitkomst is dan
     * [SessieEinde.ONBEKEND].
     */
    fun beeindigSessie(sessieId: String, ontvanger: Identificatienummer): SessieEinde
}
