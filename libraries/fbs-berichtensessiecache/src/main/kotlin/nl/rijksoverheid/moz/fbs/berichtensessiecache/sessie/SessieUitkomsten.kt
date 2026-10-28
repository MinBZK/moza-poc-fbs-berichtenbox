package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

/**
 * Een gestarte sessie.
 *
 * [sessieId] is een credential: alleen de houder mag het zien, dus niet loggen en niet in een URL.
 */
data class SessieStart(
    val sessieId: String,
) {
    // Een data class zet elk veld in toString, en een toString belandt makkelijk in een log.
    override fun toString(): String =
        "SessieStart(sessieId=***)"
}

/** Wat het beëindigen van een sessie heeft gedaan. */
enum class SessieEinde {
    /** Geen lopende sessie met dit id voor deze ontvanger. */
    ONBEKEND,

    /** De sessie is beëindigd. */
    BEEINDIGD,
}

