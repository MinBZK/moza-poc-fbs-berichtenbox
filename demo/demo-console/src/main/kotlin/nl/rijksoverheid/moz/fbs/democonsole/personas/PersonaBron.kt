package nl.rijksoverheid.moz.fbs.democonsole.personas

/**
 * Waar de berichten van een persona vandaan komen: uit de keten, of uit een gegenereerde
 * dataset. De waarde staat per persona in de configuratie en kent geen terugval — een
 * berichtenbox die ongevraagd op de dataset uitkomt, toont verzonnen berichten alsof ze
 * uit de keten komen.
 */
enum class PersonaBron {

    KETEN,
    DATASET,
    ;

    companion object {

        fun van(waarde: String): PersonaBron =
            entries.firstOrNull { it.name.equals(waarde, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "onbekende bron '$waarde'; toegestaan: ${entries.joinToString { it.name.lowercase() }}",
                )
    }
}
