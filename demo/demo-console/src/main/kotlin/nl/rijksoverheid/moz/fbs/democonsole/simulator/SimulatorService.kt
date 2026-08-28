package nl.rijksoverheid.moz.fbs.democonsole.simulator

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * De bediening van de gesimuleerde magazijnen tijdens een demo.
 *
 * De knop "zet er k van de n aan" bestaat sinds de stub-magazijnen en blijft: hij laat in één
 * beweging zien wat een ondernemer merkt als een deel van zijn organisaties eruit ligt. Wat eronder
 * zit is nieuw — geen 503-overlay per stub, maar het gedrag van de simulator, dat straks ook traag
 * en haperend kan zijn.
 *
 * Het aantal komt van de simulator zelf en niet uit een eigen instelling. Twee getallen die uit
 * elkaar kunnen lopen zijn er één te veel: de console zou dan magazijnen aansturen die er niet zijn,
 * of er een paar overslaan.
 */
@ApplicationScoped
class SimulatorService(@param:RestClient private val beheer: SimulatorBeheerClient) {

    /** Wat de simulator voorstelt, op OIN gesorteerd zodat "de eerste k" een vaste betekenis heeft. */
    fun magazijnen(): List<SimulatorMagazijn> = beheer.magazijnen().sortedBy { it.oin }

    /**
     * Zet de eerste `k` magazijnen op normaal en de rest op storing.
     *
     * In één aanroep en niet honderd losse: bij honderd magazijnen zou de knop anders trager zijn
     * dan de demo die hij moet ondersteunen.
     */
    fun zetActief(k: Int): Map<String, Int> {
        val alle = magazijnen()

        require(k in 0..alle.size) { "k moet tussen 0 en ${alle.size} liggen (kreeg $k)" }

        val uitkomst = beheer.zetGedrag(
            BulkGedragVerzoek(
                alle.mapIndexed { index, magazijn ->
                    GedragAanpassing(magazijn.oin, if (index < k) NORMAAL else STORING)
                },
            ),
        )

        check(uitkomst.onbekend.isEmpty()) {
            "De simulator kende ${uitkomst.onbekend.size} van de aangeboden magazijnen niet: ${uitkomst.onbekend}"
        }

        return mapOf("actief" to k, "totaal" to alle.size)
    }

    /**
     * Alles terug naar de begintoestand: berichten weg én het gedrag terug naar de vastgelegde
     * verdeling. Dat laatste hoort erbij — anders staat een magazijn dat tijdens de vorige demo op
     * storing is gezet er de volgende keer nog zo bij.
     */
    fun herstel(): Map<String, Int> {
        val uitkomst = beheer.legen()

        return mapOf("berichten" to uitkomst.berichten, "magazijnen" to uitkomst.magazijnenTeruggezet)
    }

    /** Zet berichten klaar voor de opgegeven ondernemers, in elk gesimuleerd magazijn. */
    fun vul(ontvangers: List<String>, berichtenPerMagazijn: Int, bijlageElke: Int): SeedUitkomst =
        beheer.seed(SeedVerzoek(ontvangers, berichtenPerMagazijn, bijlageElke))

    private companion object {
        const val NORMAAL = "NORMAAL"

        /** Een magazijn dat "uit" staat, geeft consequent een serverfout — zoals de stubs deden. */
        const val STORING = "STUK"
    }
}
