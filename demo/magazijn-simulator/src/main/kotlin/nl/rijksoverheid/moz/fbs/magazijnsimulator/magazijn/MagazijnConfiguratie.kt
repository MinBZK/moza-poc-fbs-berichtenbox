package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.Gedrag
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.GedragModus
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.GedragVerdeling

/** Wat de configuratie over één magazijn zegt, na validatie. */
data class MagazijnInstelling(val naam: String, val gedrag: Gedrag)

/**
 * Leest en valideert de geconfigureerde set magazijnen.
 *
 * Apart van [GesimuleerdeMagazijnen] gehouden zodat de validatie zonder CDI en zonder database te
 * toetsen is — het is pure invoerverwerking, en dat hoort niet aan een draaiende applicatie vast te
 * zitten om na te lopen.
 *
 * Fail-fast is hier het punt: de uitvraag krijgt zijn register uit hetzelfde generator-artefact,
 * dus een fout die de boot passeert komt pas bij het eerste verkeer boven — midden in een demo, bij
 * één van de honderd magazijnen, met de oorzaak ver weg.
 */
object MagazijnConfiguratie {

    private val OIN_PATROON = Regex("^[0-9]{20}$")

    fun valideer(entries: Map<String, MagazijnSimulatorConfig.Inschrijving>): Map<String, MagazijnInstelling> {
        // `check` en niet `require`, net als de controles hieronder: dit zijn alle fouten in de
        // configuratie van de omgeving, geen fouten van een aanroeper. Twee exception-types voor
        // hetzelfde soort fout worden vanzelf contract zodra een test ze uit elkaar houdt.
        check(entries.isNotEmpty()) {
            "Geen magazijnen geconfigureerd (magazijnsimulator.magazijnen.\"<OIN>\".naam)"
        }

        return entries.entries.associate { (key, entry) ->
            check(OIN_PATROON.matches(key)) {
                "Ongeldige OIN-key in magazijn-simulator-config: '$key' moet precies 20 cijfers zijn " +
                    "(magazijnsimulator.magazijnen.\"$key\".naam)"
            }

            check(key.any { it != '0' }) {
                "Ongeldige OIN-key in magazijn-simulator-config: '$key' kan niet geheel uit nullen bestaan"
            }

            val naam = entry.naam().trim()

            check(naam.isNotBlank()) {
                "magazijnsimulator.magazijnen.\"$key\".naam mag niet leeg of alleen whitespace zijn"
            }

            key to MagazijnInstelling(naam = naam, gedrag = gedragVan(key, entry))
        }
    }

    /**
     * Het gedrag komt uit de vastgelegde verdeling bij het volgnummer, tenzij de configuratie er
     * expliciet iets anders neerzet. Zo hoeft het generatiescript alleen een nummer te schrijven en
     * staat de verdeling op één plek, terwijl één magazijn bewust anders zetten toch mogelijk blijft.
     */
    private fun gedragVan(oin: String, entry: MagazijnSimulatorConfig.Inschrijving): Gedrag {
        val expliciet = entry.gedrag().orElse(null)

        if (expliciet != null) {
            val modus = GedragModus.entries.firstOrNull { it.name == expliciet.trim().uppercase() }

            checkNotNull(modus) {
                "magazijnsimulator.magazijnen.\"$oin\".gedrag is '$expliciet'; toegestaan: " +
                    GedragModus.entries.joinToString()
            }

            return Gedrag.standaardVoor(modus)
        }

        val index = entry.index()

        if (!index.isPresent) return Gedrag.NORMAAL

        check(index.asInt >= 1) {
            "magazijnsimulator.magazijnen.\"$oin\".index begint bij 1 (kreeg ${index.asInt})"
        }

        return GedragVerdeling.voorIndex(index.asInt)
    }
}
