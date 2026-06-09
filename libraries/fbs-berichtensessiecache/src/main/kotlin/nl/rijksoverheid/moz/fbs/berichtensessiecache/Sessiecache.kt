package nl.rijksoverheid.moz.fbs.berichtensessiecache

import io.smallrye.mutiny.Multi
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
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
 * Het streaming [ophalen] heeft een eigen asynchroon foutkanaal (`Multi`-failure) en
 * valt buiten deze hiërarchie.
 */
interface Sessiecache {

    /**
     * Berichtenlijst voor [ontvanger], gepagineerd, optioneel gefilterd op
     * [afzender] en/of [map]. Vereist een afgeronde ophaling (zie foutsemantiek).
     * `pagina` default 0; `paginaGrootte` default 20, gecapt op 100.
     */
    fun lijst(
        ontvanger: Identificatienummer,
        pagina: Int? = null,
        paginaGrootte: Int? = null,
        afzender: String? = null,
        map: String? = null,
    ): BerichtenPagina

    /**
     * Volledig-tekst zoeken (RediSearch) in de berichten van [ontvanger].
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
     * Volledig bericht (inclusief inhoud en bijlage-metadata), of `null` als het
     * niet bestaat of niet van [ontvanger] is. Vereist een afgeronde ophaling.
     */
    fun bericht(ontvanger: Identificatienummer, berichtId: UUID): Bericht?

    /**
     * Merge-PATCH op leesstatus en/of map. Minimaal één van beide moet gezet zijn
     * (anders 400). Retourneert het bijgewerkte bericht, of `null` als het bericht
     * niet bestaat of niet van [ontvanger] is.
     */
    fun werkBerichtBij(
        ontvanger: Identificatienummer,
        berichtId: UUID,
        status: Leesstatus?,
        map: String?,
    ): Bericht?

    /**
     * Idempotente cache-invalidate: "niet in cache" is geen fout (TTL kan verlopen
     * zijn, of een andere instance heeft de dual-write-invalidate al uitgevoerd).
     */
    fun verwijder(ontvanger: Identificatienummer, berichtId: UUID)

    /**
     * Orkestreert het ophalen van berichten uit de voor [ontvanger] relevante
     * magazijnen en vult de cache; retourneert een SSE-compatible event-stream
     * met per-magazijn voortgang en een afsluitend GEREED/FOUT-event. Gooit 409
     * wanneer er al een ophaling loopt voor deze ontvanger (atomaire Redis-lock).
     */
    fun ophalen(ontvanger: Identificatienummer): Multi<MagazijnEvent>

    /**
     * Schrijft één bericht bij in een bestaande, actieve sessie (aanmeld-pad).
     * Gooit 400 wanneer `bericht.ontvanger` niet overeenkomt met [ontvanger] of
     * wanneer het bericht de defensieve limieten overschrijdt; 404 wanneer er
     * geen actieve sessie is voor deze ontvanger.
     */
    fun schrijfBericht(ontvanger: Identificatienummer, bericht: Bericht): Bericht
}
