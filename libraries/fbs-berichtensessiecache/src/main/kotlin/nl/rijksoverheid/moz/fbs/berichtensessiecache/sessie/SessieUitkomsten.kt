package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.time.Instant

/**
 * Een gestarte sessie.
 *
 * [sessieId] is een credential: alleen de houder mag het zien, dus niet loggen en niet in een URL.
 * [uiterlijkGeldigTot] is het verloop als er verder niets gebeurt; elk verzoek in de sessie schuift
 * het op, tot de maximale duur. [restenOpgeruimd] meldt dat de start gegevens van een eerdere,
 * verlopen sessie heeft gewist, of een opruiming overnam die nog op de veegtaak wachtte — dat
 * wissen hoort in het logboek.
 */
data class SessieStart(
    val sessieId: String,
    val uiterlijkGeldigTot: Instant,
    val restenOpgeruimd: Boolean,
) {
    // Een data class zet elk veld in toString, en een toString belandt makkelijk in een log.
    override fun toString(): String =
        "SessieStart(sessieId=***, uiterlijkGeldigTot=$uiterlijkGeldigTot, restenOpgeruimd=$restenOpgeruimd)"
}

/** Wat het beëindigen van een sessie heeft gedaan. */
enum class SessieEinde {
    /** Geen lopende sessie met dit id voor deze ontvanger, en er is niets gewist. */
    ONBEKEND,

    /** De sessie is beëindigd; de ontvanger heeft nog andere lopende sessies, dus zijn gegevens blijven. */
    BEEINDIGD,

    /** Er loopt geen sessie meer voor deze ontvanger en zijn gegevens zijn gewist. */
    OPGERUIMD,
}

/** Het opruimen van één ontvanger wiens laatste sessie verlopen is. */
data class Opruiming(
    val ontvanger: Identificatienummer,
    val uitkomst: OpruimUitkomst,
) {
    // Zoals bij SessieStart: toString belandt makkelijk in een log, en de waarde kan een BSN zijn.
    override fun toString(): String = "Opruiming(ontvanger=${ontvanger.type}:***, uitkomst=$uitkomst)"
}

/** Wat het opruimen van een ontvanger wiens laatste sessie verlopen is, heeft gedaan. */
enum class OpruimUitkomst {
    /** Er liep geen sessie meer; de gegevens zijn gewist en de ontvanger staat niet meer in de verloopindex. */
    GEWIST,

    /**
     * Het opruimen faalde. De ontvanger staat nog in de verloopindex, maar een veeginterval (minstens
     * 30 s) naar achteren, zodat de ontvangers achter hem aan de beurt komen; daarna probeert een
     * veegronde het opnieuw. Een deel van de gegevens kan al gewist zijn.
     */
    MISLUKT,

    /**
     * Geen antwoord binnen de wachttijd. Het opruimen kan in Redis alsnog zijn uitgevoerd, of niet;
     * staan de gegevens er nog, dan wist de Redis-TTL ze uiterlijk na de maximale duur plus de
     * vangnet-marge.
     */
    ONBEVESTIGD,
}
