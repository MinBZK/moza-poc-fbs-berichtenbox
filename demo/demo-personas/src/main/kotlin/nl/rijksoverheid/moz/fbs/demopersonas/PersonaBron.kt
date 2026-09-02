package nl.rijksoverheid.moz.fbs.demopersonas

import com.fasterxml.jackson.annotation.JsonValue
import java.util.Locale

/**
 * Hoe een berichtenbox de herkomst van deze persona's berichten benoemt. Beide waarden halen op
 * via de keten; `dataset` merkt de keuzelijst-optie aan als gegenereerde vulling. Niet te verwarren
 * met `dataset/basis.json` in de demo-console: die berichten worden bij de echte magazijnen aangeleverd
 * en horen dus bij [KETEN].
 *
 * Geen default en geen terugval: stil op `keten` uitkomen laat een berichtenbox gegenereerde
 * inhoud presenteren zonder dat merkteken, alsof ze echt is.
 */
enum class PersonaBron {

    KETEN,
    DATASET,
    ;

    /**
     * De vorm die over de lijn gaat; [van] leest hem terug, hoofdletterongevoelig. `@JsonValue`
     * zodat elke serialisatie hem gebruikt: een producent die `name` zou schrijven levert `KETEN`,
     * en een afnemer die op `keten` zoekt vindt dan stil niets meer.
     */
    @get:JsonValue
    val wire: String get() = name.lowercase(Locale.ROOT)

    companion object {

        fun van(waarde: String): PersonaBron =
            entries.firstOrNull { it.name.equals(waarde, ignoreCase = true) }
                ?: throw IllegalArgumentException("onbekende bron; toegestaan: ${entries.joinToString { it.wire }}")
    }
}
