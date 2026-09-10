package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import java.net.URI

/** Zie [Foutcode]; top-level omdat een enum-entry vóór zijn companion initialiseert. */
private const val NAMESPACE = "urn:fbs:fout:"

/**
 * Het kenmerk dat elk foutantwoord in `type` draagt, zodat een afnemer ziet dát het antwoord van
 * de keten komt en om welke situatie het gaat.
 *
 * Dit is een kopie van de codes uit `fbs-common`, met opzet en niet uit gemak. De belofte van de
 * simulator is dat zijn antwoorden — ook zijn foutantwoorden — niet van die van een echt magazijn
 * te onderscheiden zijn; een simulator die als enige `about:blank` teruggeeft, is aan zijn
 * foutpad herkenbaar. Die library hier binnenhalen kan niet: ze brengt haar eigen JAX-RS-providers
 * mee, die zich in deze applicatie zouden registreren naast de mappers die de simulator juist
 * bewust zelf heeft.
 *
 * Alleen de codes die een magazijn kan produceren. De sessie- en ophaal-codes uit de keten
 * (`ophalen-bezig`, `geen-actieve-sessie`, …) horen bij de berichten-uitvraag en zouden hier een
 * situatie beschrijven die dit onderdeel niet kent.
 */
enum class Foutcode(val code: String) {

    /** Het bericht bestaat niet, of hoort bij een andere ontvanger op een pad dat dat niet onthult. */
    BERICHT_ONBEKEND("bericht-onbekend"),

    /** Het bericht bestond, de ontvanger verwijderde het; alleen ná een geslaagde eigenaar-check. */
    BERICHT_VERWIJDERD("bericht-verwijderd"),

    /** Het opgevraagde pad bestaat niet — een onbekend magazijn of een pad buiten de API. */
    NIET_GEVONDEN("niet-gevonden"),

    /** Het verzoek zelf klopt niet: validatie, ontbrekende header, niet-ondersteund mediatype. */
    ONGELDIG_VERZOEK("ongeldig-verzoek"),

    /** Ontbrekende of ontoereikende toegang. */
    GEEN_TOEGANG("geen-toegang"),

    /** Het verzoek botst met de huidige toestand van de resource. */
    CONFLICT("conflict"),

    /** Tijdelijke storing waarop opnieuw proberen zin heeft. */
    TIJDELIJK_NIET_BESCHIKBAAR("tijdelijk-niet-beschikbaar"),

    /** Een schakel verderop hapert; in de simulator het gesimuleerde storingsgedrag. */
    KETEN_FOUT("keten-fout"),

    /** Onverwachte fout; `instance` draagt het correlatie-id. */
    INTERNE_FOUT("interne-fout"),
    ;

    val uri: URI = URI.create(NAMESPACE + code)

    companion object {

        /**
         * De code die bij een status hoort als de throw-site er zelf geen meegaf. Dekt ook de
         * statussen die het framework produceert (405, 415, 406), zodat geen enkel antwoord op
         * `about:blank` blijft staan.
         *
         * [BERICHT_ONBEKEND] is hier onbereikbaar: een `404` op een onbekend pad is geen onbekend
         * bericht, en die terugval zou de afnemer een uitspraak over een bericht laten doen die er
         * niet is. Voor `410` ligt dat anders — die status ontstaat alleen waar dit magazijn zelf
         * vaststelt dat een bericht verwijderd is.
         */
        fun voorStatus(status: Int): Foutcode = when (status) {
            401, 403 -> GEEN_TOEGANG
            404 -> NIET_GEVONDEN
            409 -> CONFLICT
            410 -> BERICHT_VERWIJDERD
            429, 503 -> TIJDELIJK_NIET_BESCHIKBAAR
            502, 504 -> KETEN_FOUT
            in 400..499 -> ONGELDIG_VERZOEK
            else -> INTERNE_FOUT
        }
    }
}
