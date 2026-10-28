package nl.rijksoverheid.moz.fbs.berichtensessiecache

/**
 * Foutsemantiek van de synchrone [Sessiecache]-facade als gesloten hiërarchie.
 *
 * De library draagt haar fouten als domein-type (deze gesloten hiërarchie) en niet als
 * transport-type met statuscode: elke consumer vertaalt zelf elk geval naar zijn eigen
 * transport. Doordat de hiërarchie `sealed` is, dwingt een nieuw foutscenario een bouwfout
 * af bij de consumer (de `when` dekt dan niet meer alle gevallen) i.p.v. een
 * runtime-verrassing waarbij een onbedoeld foutgeval verkeerd bij de gebruiker terechtkomt.
 * Vgl. het bestaande
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

    /**
     * Er staat voor de ontvanger geen opgehaalde lijst in de cache om een aangemeld bericht in bij
     * te schrijven: er is nog niet opgehaald, of de lijst is met de laatste sessie opgeruimd.
     */
    class GeenLijstInCache(message: String) : SessiecacheException(message)

    /**
     * De ontvanger verwijderde dit bericht zelf. Alleen bekend zolang de tombstone bestaat: die
     * hoort bij de gegevens van de ontvanger en verdwijnt met het opruimen na zijn laatste sessie.
     * In een volgende sessie is het weer een gewone misser. Wordt nooit gegooid voor een bericht van
     * een andere ontvanger: die krijgt de misser die hij ook voor een onbekend bericht zou krijgen.
     */
    class BerichtVerwijderd(message: String) : SessiecacheException(message)

    /**
     * Het sessie-id is geen lopende sessie van deze ontvanger: onbekend, verlopen of van een ander.
     * Eén geval, zodat een aanroeper niet kan aftasten welke id's ooit bestonden.
     */
    class SessieOngeldig(message: String) : SessiecacheException(message)
}
