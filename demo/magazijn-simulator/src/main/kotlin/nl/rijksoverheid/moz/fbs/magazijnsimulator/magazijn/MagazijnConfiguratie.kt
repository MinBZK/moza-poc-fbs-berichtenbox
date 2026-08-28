package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

/**
 * Leest en valideert de geconfigureerde set magazijnen: `magazijnsimulator.magazijnen."<OIN>".naam`.
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

    /** OIN naar naam, gevalideerd. Gooit bij een lege set of een onbruikbare regel. */
    fun valideer(entries: Map<String, MagazijnSimulatorConfig.Inschrijving>): Map<String, String> {
        // `check` en niet `require`, net als de controles hieronder: dit zijn alle vier fouten in de
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

            key to naam
        }
    }
}
