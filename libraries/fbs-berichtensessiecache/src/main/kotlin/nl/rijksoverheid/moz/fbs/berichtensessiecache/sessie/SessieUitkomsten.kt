package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

/**
 * Een gestarte sessie.
 *
 * [sessieId] is een credential: alleen de houder mag het zien, dus niet loggen en niet in een URL.
 * [restenOpgeruimd] meldt dat de start gegevens heeft gewist die er zonder lopende sessie nog
 * stonden — dat wissen hoort in het logboek.
 */
data class SessieStart(
    val sessieId: String,
    val restenOpgeruimd: Boolean,
) {
    // Een data class zet elk veld in toString, en een toString belandt makkelijk in een log.
    override fun toString(): String =
        "SessieStart(sessieId=***, restenOpgeruimd=$restenOpgeruimd)"
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

/** Wat het opruimen van de gegevens van een ontvanger heeft gedaan, zoals het in het logboek komt. */
enum class OpruimUitkomst {
    /** De gegevens zijn gewist. */
    GEWIST,

    /**
     * Geen antwoord binnen de timeout (`facade-await-timeout-seconds`). Het opruimen kan in Redis
     * alsnog zijn uitgevoerd, of niet.
     */
    ONBEVESTIGD,
}
