package nl.rijksoverheid.moz.fbs.democonsole.personas

import java.util.Locale

/**
 * Hoe een berichtenbox de inhoud van deze persona presenteert: als uitvraag bij de keten, of als
 * gegenereerde dataset buiten de keten om. Niet te verwarren met `dataset/basis.json` in deze
 * module: die berichten worden juist bij de echte magazijnen aangeleverd en horen dus bij [KETEN].
 *
 * Geen default en geen terugval: stil op `keten` uitkomen laat een berichtenbox verzonnen inhoud
 * presenteren alsof ze uit de keten komt.
 */
enum class PersonaBron {

    KETEN,
    DATASET,
    ;

    /** De vorm die over de lijn gaat; ook wat [van] accepteert. */
    val wire: String get() = name.lowercase(Locale.ROOT)

    companion object {

        fun van(waarde: String): PersonaBron =
            entries.firstOrNull { it.name.equals(waarde, ignoreCase = true) }
                ?: throw IllegalArgumentException("onbekende bron '$waarde'; toegestaan: ${entries.joinToString { it.wire }}")
    }
}
