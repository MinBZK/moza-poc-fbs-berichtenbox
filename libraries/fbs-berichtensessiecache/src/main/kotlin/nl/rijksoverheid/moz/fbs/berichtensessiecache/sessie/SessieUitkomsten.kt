package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.time.Instant

/**
 * Hoe lang een lopende sessie geldig is. [geldigTot] is het verloop bij inactiviteit: elk verzoek in
 * de sessie schuift het op, nooit voorbij [uiterlijkGeldigTot]. Dat is het harde plafond van de
 * sessie: start + maximale duur, of het eerdere plafond dat de start meekreeg.
 */
data class SessieGeldigheid(val geldigTot: Instant, val uiterlijkGeldigTot: Instant)

/**
 * Een gestarte sessie.
 *
 * [sessieId] is een credential: alleen de houder mag het zien, dus niet loggen en niet in een URL.
 * [restenOpgeruimd] meldt dat de start gegevens van een eerdere, verlopen sessie heeft gewist, of een
 * opruiming overnam die nog op de veegtaak wachtte — dat wissen hoort in het logboek.
 */
data class SessieStart(
    val sessieId: String,
    val geldigheid: SessieGeldigheid,
    val restenOpgeruimd: Boolean,
) {
    // Een data class zet elk veld in toString, en een toString belandt makkelijk in een log.
    override fun toString(): String =
        "SessieStart(sessieId=***, geldigheid=$geldigheid, restenOpgeruimd=$restenOpgeruimd)"
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
sealed interface Opruiming {
    val uitkomst: OpruimUitkomst

    /** Het opruimen van [ontvanger]; hoort met hem als betrokkene in het logboek. */
    data class VanOntvanger(val ontvanger: Identificatienummer, override val uitkomst: OpruimUitkomst) : Opruiming {
        // Zoals bij SessieStart: toString belandt makkelijk in een log, en de waarde kan een BSN zijn.
        override fun toString(): String = "Opruiming.VanOntvanger(ontvanger=${ontvanger.type}:***, uitkomst=$uitkomst)"
    }

    /**
     * Een opruiming waarvan de leesbare ontvanger niet meer bekend is: die verliep met zijn
     * vangnet-TTL terwijl de index-entry nog wachtte, of was onleesbaar. Er kan dan geen logregel met
     * betrokkene komen. [kenmerk] onderscheidt de entry binnen een veegronde; het is een terug te
     * rekenen hash van de ontvanger, dus niet loggen.
     */
    data class ZonderOntvanger(val kenmerk: String, override val uitkomst: OpruimUitkomst) : Opruiming {
        override fun toString(): String = "Opruiming.ZonderOntvanger(kenmerk=***, uitkomst=$uitkomst)"
    }
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
     * Geen antwoord binnen de timeout (`facade-await-timeout-seconds`), of de veegronde werd
     * onderbroken. Het opruimen kan in Redis alsnog zijn uitgevoerd, of niet. Liep het niet, dan staat
     * de ontvanger nog in de verloopindex (na een timeout net als bij [MISLUKT] een veeginterval naar
     * achteren) en meldt een latere ronde het opruimen alsnog.
     */
    ONBEVESTIGD,
}
