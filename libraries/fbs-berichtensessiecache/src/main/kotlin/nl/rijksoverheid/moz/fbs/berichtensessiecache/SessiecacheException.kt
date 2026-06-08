package nl.rijksoverheid.moz.fbs.berichtensessiecache

/**
 * Foutsemantiek van de synchrone [Sessiecache]-facade als gesloten hiërarchie.
 *
 * De library heeft geen HTTP-laag, dus draagt zij haar fouten niet langer via een
 * transport-type ([jakarta.ws.rs.WebApplicationException] + statuscode): consumers
 * vertalen elk geval exhaustief naar hun eigen transport. Doordat de hiërarchie
 * `sealed` is, dwingt een nieuw foutscenario een bouwfout af bij de consumer (de
 * `when` is niet langer exhaustief) i.p.v. een runtime-verrassing waarbij een
 * onbedoeld foutgeval verkeerd bij de gebruiker terechtkomt. Vgl. het bestaande
 * [nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResult].
 *
 * Het streaming-ophaalpad ([Sessiecache.ophalen]) heeft een eigen, asynchroon
 * foutkanaal (de `Multi`-failure) en valt buiten deze hiërarchie.
 */
sealed class SessiecacheException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** Nog geen ophaling gestart voor deze ontvanger: de cache is (nog) leeg. */
    class NogNietGevuld(message: String) : SessiecacheException(message)

    /** Een ophaling loopt; de cache is mogelijk nog incompleet. */
    class OphalenBezig(message: String) : SessiecacheException(message)

    /** De laatste ophaling is mislukt; de ontvanger moet het ophalen opnieuw starten. */
    class OphalenMislukt(message: String) : SessiecacheException(message)

    /** Cache-opslag tijdelijk onbereikbaar of schrijf-contentie; retriable. */
    class Onbereikbaar(message: String, cause: Throwable? = null) : SessiecacheException(message, cause)

    /** Cache-data onleesbaar (deserialisatiefout of corruptie); niet retriable. */
    class Onleesbaar(message: String, cause: Throwable? = null) : SessiecacheException(message, cause)

    /** Ongeldige invoer in een facade-call (lege patch, ontvanger-mismatch, limiet-overschrijding). */
    class OngeldigeInvoer(message: String, cause: Throwable? = null) : SessiecacheException(message, cause)

    /** Geen actieve sessie voor de ontvanger (aanmeld-schrijfpad). */
    class GeenActieveSessie(message: String) : SessiecacheException(message)
}
