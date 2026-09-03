package nl.rijksoverheid.moz.fbs.common.exception

import java.net.URI

/**
 * Namespace van elk kenmerk; het segment `fout` houdt ruimte voor andere `urn:fbs`-soorten.
 * Top-level en niet in het companion object: een enum-entry initialiseert vóór zijn companion,
 * dus een companion-constante is vanuit de `uri`-initializer niet bereikbaar.
 */
private const val NAMESPACE = "urn:fbs:fout:"

/**
 * Het machineleesbare kenmerk op een foutantwoord, als RFC 9457 `type`-URI.
 *
 * Twee dingen moet een afnemer aan een foutantwoord kunnen zien: dát het van deze keten komt,
 * en om welke situatie het gaat. Een `404` met `about:blank` doet geen van beide — die is niet
 * te onderscheiden van een `404` die een proxy onderweg verzint, en dekt situaties die voor de
 * gebruiker sterk verschillen onder één antwoord. Elk foutantwoord draagt daarom een code uit
 * deze lijst; blijft er ergens `about:blank` staan, dan is dat antwoord niet van ons.
 *
 * De `urn:`-vorm boven een `https://`-vorm omdat een dereferenceerbare URI de belofte van een
 * blijvende, gehoste pagina per code met zich meebrengt — een belofte die dood linkt zodra die
 * pagina verhuist. De waarden zijn contract voor afnemers: wijzig een bestaande code niet, voeg
 * er een toe.
 */
enum class Foutcode(val code: String) {

    /**
     * Het bericht bestaat niet, hoort bij een andere ontvanger, of zat niet in de opgehaalde set.
     * Die drie zijn met opzet één uitkomst: een eigen code voor "bestaat wel, maar niet van jou"
     * zou het bestaan van andermans berichten aftastbaar maken.
     */
    BERICHT_ONBEKEND("bericht-onbekend"),

    /**
     * De ontvanger verwijderde dit bericht zelf. Alleen bekend zolang de sessie leeft en alleen
     * voor verwijderingen die via deze keten liepen: het uitblijven van deze code bewijst dus
     * niet dat er niets verwijderd is.
     */
    BERICHT_VERWIJDERD("bericht-verwijderd"),

    /** Nog geen ophaalronde geweest voor deze ontvanger: eerst `_ophalen` starten. */
    NOG_NIET_OPGEHAALD("nog-niet-opgehaald"),

    /** Er loopt een ophaalronde; wachten en opnieuw lezen, geen foutmelding waard. */
    OPHALEN_BEZIG("ophalen-bezig"),

    /** De vorige ophaalronde strandde; opnieuw `_ophalen` is de weg vooruit. */
    OPHALEN_MISLUKT("ophalen-mislukt"),

    /** Tijdelijke storing waarop opnieuw proberen zin heeft; `Retry-After` staat erbij. */
    TIJDELIJK_NIET_BESCHIKBAAR("tijdelijk-niet-beschikbaar"),

    /** Geen actieve sessie voor deze ontvanger; er is niets om een bericht in bij te schrijven. */
    GEEN_ACTIEVE_SESSIE("geen-actieve-sessie"),

    /** De opgevraagde resource bestaat niet — een onbekend pad, niet een onbekend bericht. */
    NIET_GEVONDEN("niet-gevonden"),

    /** Het verzoek zelf klopt niet: validatie, ontbrekende header, niet-ondersteund mediatype. */
    ONGELDIG_VERZOEK("ongeldig-verzoek"),

    /** Ontbrekende of ontoereikende toegang. */
    GEEN_TOEGANG("geen-toegang"),

    /** Het verzoek botst met de huidige toestand van de resource. */
    CONFLICT("conflict"),

    /** Een schakel verderop in de keten hapert; niet de fout van de afnemer. */
    KETEN_FOUT("keten-fout"),

    /**
     * De configuratie van de keten spreekt zichzelf tegen. Apart van [INTERNE_FOUT] omdat het
     * antwoord hier iets anders zegt: opnieuw proberen heeft geen zin, hier moet een beheerder aan te pas.
     */
    CONFIGURATIE_MISMATCH("configuratie-mismatch"),

    /** Onverwachte fout aan onze kant; `instance` draagt de correlatie-id voor support. */
    INTERNE_FOUT("interne-fout"),
    ;

    val uri: URI = URI.create(NAMESPACE + code)

    companion object {

        /**
         * De code die bij een status hoort als de throw-site er zelf geen meegaf. Dekt de
         * statussen die het framework produceert (405, 415, 406) en elke `WebApplicationException`
         * die van vóór dit kenmerk stamt, zodat geen enkel antwoord op `about:blank` blijft staan.
         *
         * Bewust generiek: de bericht-specifieke codes zijn hier onbereikbaar. Een `404` op een
         * onbekend pad is geen onbekend bericht, en die terugval zou de afnemer een uitspraak over
         * een bericht laten doen die er niet is.
         */
        fun voorStatus(status: Int): Foutcode = when (status) {
            401, 403 -> GEEN_TOEGANG
            404 -> NIET_GEVONDEN
            // Wél bericht-specifiek, in tegenstelling tot 404: geen enkel framework-pad
            // produceert een 410. Die status ontstaat alleen waar de keten zelf vaststelt dat
            // een bericht verwijderd is, ook wanneer die vaststelling een hop verderop viel.
            410 -> BERICHT_VERWIJDERD
            409 -> CONFLICT
            502 -> KETEN_FOUT
            503 -> TIJDELIJK_NIET_BESCHIKBAAR
            in 400..499 -> ONGELDIG_VERZOEK
            else -> INTERNE_FOUT
        }
    }
}
