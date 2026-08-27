package nl.rijksoverheid.moz.fbs.democonsole.personas

import java.util.Locale

/**
 * Hoe een berichtenbox de herkomst van deze persona's berichten benoemt. Beide waarden halen op
 * via de keten; `dataset` merkt de keuzelijst-optie aan als gegenereerde vulling. Niet te verwarren
 * met `dataset/basis.json` in deze module: die berichten worden bij de echte magazijnen aangeleverd
 * en horen dus bij [KETEN].
 *
 * Geen default en geen terugval: stil op `keten` uitkomen laat een berichtenbox gegenereerde
 * inhoud presenteren zonder dat merkteken, alsof ze echt is.
 */
enum class PersonaBron {

    KETEN,
    DATASET,
    ;

    /** De vorm die over de lijn gaat; [van] leest hem terug, hoofdletterongevoelig. */
    val wire: String get() = name.lowercase(Locale.ROOT)

    companion object {

        fun van(waarde: String): PersonaBron =
            entries.firstOrNull { it.name.equals(waarde, ignoreCase = true) }
                ?: throw IllegalArgumentException("onbekende bron; toegestaan: ${entries.joinToString { it.wire }}")
    }
}
