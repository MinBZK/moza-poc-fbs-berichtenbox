package nl.rijksoverheid.moz.fbs.democonsole.simulator

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.omgeving.OmgevingConfig
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.logging.Logger

/** Hoeveel gesimuleerde magazijnen er zijn en hoeveel er zonder storing staan. */
data class SimulatorStand(val actief: Int, val totaal: Int)

/**
 * Wat het terugzetten van de gesimuleerde magazijnen opleverde.
 *
 * [overgeslagen] draagt de reden wanneer er níéts gebeurd is — deze omgeving kent geen simulator,
 * of hij antwoordde niet. Dat is geen fout waar het terugzetten een deelstap is: het legen en
 * vullen van de twee echte magazijnen staat er los van, en die als mislukt melden laat de bediener
 * ze een tweede keer legen.
 */
data class GesimuleerdHerstel(
    val berichten: Int = 0,
    val magazijnen: Int = 0,
    val overgeslagen: String? = null,
)

/**
 * De bediening van de gesimuleerde magazijnen tijdens een demo.
 *
 * De knop "zet er k van de n aan" laat in één beweging zien wat een ondernemer merkt als een deel
 * van zijn organisaties eruit ligt. Eronder zit het gedrag van de simulator, dat per magazijn
 * normaal, traag, haperend, kapot, onbereikbaar, weigerend of misvormd kan zijn; deze knop gebruikt
 * daar twee van.
 *
 * Het aantal komt van de simulator zelf en niet uit een eigen instelling. Twee getallen die uit
 * elkaar kunnen lopen zijn er één te veel: de console zou dan magazijnen aansturen die er niet zijn,
 * of er een paar overslaan.
 */
@ApplicationScoped
class SimulatorService(
    @param:RestClient private val beheer: SimulatorBeheerClient,
    private val omgeving: OmgevingConfig,
) {

    private val log = Logger.getLogger(SimulatorService::class.java.name)

    /** Wat de simulator voorstelt, op OIN gesorteerd zodat "de eerste k" een vaste betekenis heeft. */
    fun magazijnen(): List<SimulatorMagazijn> = beheer.magazijnen().sortedBy { it.oin }

    /**
     * Hetzelfde type als [zetActief] teruggeeft, want de statusbalk van het paneel leest beide met
     * dezelfde formatter; twee vormen voor hetzelfde feit zouden er één van de twee stil laten
     * breken.
     */
    fun status(): SimulatorStand {
        val alle = magazijnen()

        return SimulatorStand(actief = alle.count { it.modus == NORMAAL }, totaal = alle.size)
    }

    /**
     * Zet de eerste `k` magazijnen op normaal en de rest op storing.
     *
     * In één aanroep en niet honderd losse: bij honderd magazijnen zou de knop anders trager zijn
     * dan de demo die hij moet ondersteunen.
     */
    fun zetActief(k: Int): SimulatorStand {
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

        return SimulatorStand(actief = k, totaal = alle.size)
    }

    /**
     * Alles terug naar de begintoestand: berichten weg én het gedrag terug naar de vastgelegde
     * verdeling. Dat laatste hoort erbij — anders staat een magazijn dat tijdens de vorige demo op
     * storing is gezet er de volgende keer nog zo bij.
     *
     * Gooit door. Voor de knop die expliciet op de simulator gericht is, is een onbereikbare
     * simulator namelijk wél de fout die je wilt zien.
     */
    fun herstel(): GesimuleerdHerstel {
        val uitkomst = beheer.legen()

        return GesimuleerdHerstel(berichten = uitkomst.berichten, magazijnen = uitkomst.magazijnenTeruggezet)
    }

    /**
     * [herstel] voor de knoppen waar de simulator een deelstap is: legen en herstellen raken ook de
     * twee echte magazijnen, en die zijn al gedaan tegen de tijd dat dit aan de beurt is.
     *
     * Overslaan is daar een uitkomst en geen fout — mits de aanroeper de reden dóórgeeft aan het
     * paneel. Doet hij dat niet, dan is dit alsnog een stille fout.
     */
    fun herstelZoMogelijk(): GesimuleerdHerstel {
        if (!omgeving.simulator()) {
            return GesimuleerdHerstel(overgeslagen = "deze omgeving kent geen magazijn-simulator")
        }

        return runCatching { herstel() }.getOrElse { fout ->
            log.warning("gesimuleerde magazijnen niet teruggezet: $fout")

            GesimuleerdHerstel(overgeslagen = fout.message ?: fout::class.simpleName.orEmpty())
        }
    }

    /** Zet berichten klaar voor de opgegeven ondernemers, in elk gesimuleerd magazijn. */
    fun vul(ontvangers: List<String>, berichtenPerMagazijn: Int, bijlageElke: Int): SeedUitkomst =
        beheer.seed(SeedVerzoek(ontvangers, berichtenPerMagazijn, bijlageElke))

    /** De standaardvulling: alle vier de ondernemers, twintig berichten per magazijn. */
    fun vulStandaard(): SeedUitkomst = vul(ONDERNEMERS, STANDAARD_PER_MAGAZIJN, STANDAARD_BIJLAGE_ELKE)

    companion object {
        /**
         * De vier ondernemers uit `demo/genereer-magazijnen.py`, in de vorm van de
         * `X-Ontvanger`-header. Ze staan hier omdat de simulator niet weet wie er in de demo
         * meespelen — hij vult berichtenbakken, hij verzint geen ondernemers.
         *
         * `OndernemersConsistentieTest` bewaakt dat deze lijst gelijk blijft aan die van het
         * generatiescript. Lopen ze uiteen, dan zet de vul-knop berichten klaar voor een ontvanger
         * die geen persona meer is, en toont de demo lege magazijnen zonder dat iets rood wordt.
         */
        val ONDERNEMERS = listOf("BSN:999993653", "KVK:90000014", "KVK:90000001", "KVK:90000003")

        /**
         * Twintig is niet toevallig: de uitvraag haalt per magazijn één pagina op en het magazijn
         * levert er standaard twintig. Daarboven demonstreer je onbedoeld dát gat.
         */
        const val STANDAARD_PER_MAGAZIJN = 20
        const val STANDAARD_BIJLAGE_ELKE = 4

        const val NORMAAL = "NORMAAL"

        /** Een magazijn dat "uit" staat, geeft consequent een serverfout. */
        const val STORING = "STUK"
    }
}
