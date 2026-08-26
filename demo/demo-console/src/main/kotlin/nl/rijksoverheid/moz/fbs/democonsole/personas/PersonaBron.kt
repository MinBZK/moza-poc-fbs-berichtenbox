package nl.rijksoverheid.moz.fbs.democonsole.personas

/**
 * Waar de berichten van een persona vandaan komen. De proeftuin toont naast de keten ook een
 * gegenereerde dataset; welke van de twee je ziet hoort een expliciete keuze te zijn, want een
 * pagina die stil terugvalt toont verzonnen berichten alsof ze uit de keten komen.
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
