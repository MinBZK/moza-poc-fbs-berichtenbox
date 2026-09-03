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
 *
 * [uitleg] is de vaste, veilige tekst die een 5xx-antwoord meekrijgt in plaats van het gemaskeerde
 * standaarddetail. Zonder die uitzondering krijgt een `503 ophalen-mislukt` het detail "onverwachte
 * interne fout, vermeld errorId bij support", wat een afnemer precies het verkeerde laat doen. De
 * tekst hangt aan de code en niet aan de exception, dus er lekt niets uit.
 */
enum class Foutcode(val code: String, val uitleg: String) {

    /**
     * Het bericht bestaat niet, hoort bij een andere ontvanger, of zat niet in de opgehaalde set.
     * Die drie zijn met opzet één uitkomst: een eigen code voor "bestaat wel, maar niet van jou"
     * zou het bestaan van andermans berichten aftastbaar maken.
     */
    BERICHT_ONBEKEND("bericht-onbekend", "Dit bericht is niet (meer) beschikbaar."),

    /**
     * De ontvanger verwijderde dit bericht zelf. Alleen bekend zolang de tombstone leeft — die
     * krijgt bij het verwijderen de sessie-TTL mee en schuift daarna niet mee met leesverkeer — en
     * alleen voor verwijderingen die via deze keten liepen. Het uitblijven van deze code bewijst
     * dus niet dat er niets verwijderd is.
     */
    BERICHT_VERWIJDERD("bericht-verwijderd", "Dit bericht is verwijderd."),

    /** Nog geen ophaalronde geweest voor deze ontvanger: eerst `_ophalen` starten. */
    NOG_NIET_OPGEHAALD("nog-niet-opgehaald", "De berichten zijn nog niet opgehaald."),

    /** Er loopt een ophaalronde; wachten en opnieuw lezen, geen foutmelding waard. */
    OPHALEN_BEZIG("ophalen-bezig", "De berichten worden opgehaald."),

    /** De vorige ophaalronde strandde; opnieuw `_ophalen` is de weg vooruit. */
    OPHALEN_MISLUKT("ophalen-mislukt", "Het ophalen van de berichten is mislukt. Haal ze opnieuw op."),

    /** Tijdelijke storing waarop opnieuw proberen zin heeft; `Retry-After` staat erbij. */
    TIJDELIJK_NIET_BESCHIKBAAR("tijdelijk-niet-beschikbaar", "Tijdelijk niet beschikbaar. Probeer het straks opnieuw."),

    /** Geen actieve sessie voor deze ontvanger; er is niets om een bericht in bij te schrijven. */
    GEEN_ACTIEVE_SESSIE("geen-actieve-sessie", "Er is geen actieve sessie voor deze ontvanger."),

    /** De opgevraagde resource bestaat niet — een onbekend pad, niet een onbekend bericht. */
    NIET_GEVONDEN("niet-gevonden", "Niet gevonden."),

    /** Het verzoek zelf klopt niet: validatie, ontbrekende header, niet-ondersteund mediatype. */
    ONGELDIG_VERZOEK("ongeldig-verzoek", "Het verzoek is ongeldig."),

    /** Ontbrekende of ontoereikende toegang. */
    GEEN_TOEGANG("geen-toegang", "Geen toegang."),

    /** Het verzoek botst met de huidige toestand van de resource. */
    CONFLICT("conflict", "Het verzoek botst met de huidige toestand."),

    /** Een schakel verderop in de keten hapert; niet de fout van de afnemer. */
    KETEN_FOUT("keten-fout", "Een andere schakel in de keten reageerde niet. Probeer het straks opnieuw."),

    /**
     * De configuratie van de keten spreekt zichzelf tegen. Apart van [INTERNE_FOUT] omdat het
     * antwoord hier iets anders zegt: opnieuw proberen heeft geen zin, hier moet een beheerder aan te pas.
     */
    CONFIGURATIE_MISMATCH(
        "configuratie-mismatch",
        "De configuratie van de keten is niet sluitend. Opnieuw proberen helpt niet; meld dit bij de beheerder.",
    ),

    /** Onverwachte fout aan onze kant; `instance` draagt de correlatie-id voor support. */
    INTERNE_FOUT("interne-fout", "Er is een onverwachte interne fout opgetreden. Vermeld errorId bij contact met support."),
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
            // Wél bericht-specifiek, in tegenstelling tot 404: wij produceren zelf nergens een 410
            // buiten de vaststelling dat een bericht verwijderd is, en die vaststelling kan een hop
            // verderop vallen (het magazijn op PATCH). Een 410 die een tussenliggende proxy verzint
            // krijgt hiermee ons kenmerk opgeplakt; dat risico wegen we lichter dan een `PATCH` op
            // een eigen verwijderd bericht die als "ongeldig verzoek" bij de afnemer landt.
            410 -> BERICHT_VERWIJDERD
            409 -> CONFLICT
            // 429 hoort niet bij ONGELDIG_VERZOEK: het verzoek was juist prima, de afnemer moet
            // wachten. 504 hoort niet bij INTERNE_FOUT: een gateway-timeout is per definitie een
            // schakel verderop.
            429 -> TIJDELIJK_NIET_BESCHIKBAAR
            502, 504 -> KETEN_FOUT
            503 -> TIJDELIJK_NIET_BESCHIKBAAR
            in 400..499 -> ONGELDIG_VERZOEK
            else -> INTERNE_FOUT
        }
    }
}
