package nl.rijksoverheid.moz.fbs.democonsole.opstartvulling

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Zet na het opstarten de basisvulling klaar in elk echt magazijn waar nog geen bericht staat.
 *
 * Een verse preview begint met lege magazijndatabases, en een lege berichtenbox is tijdens een demo
 * niet van een kapotte keten te onderscheiden. De gesimuleerde magazijnen vullen zichzelf bij het
 * opstarten; de twee echte draaien stelselcode die van geen demo weet. Deze console kent ze wél: hij
 * telt hun berichten al voor de toestandsbalk en levert de basisvulling via hun aanlever-API.
 *
 * **Per magazijn, en alleen als het leeg is.** Een magazijn met berichten blijft ongemoeid, ook als
 * het er minder zijn dan de basisvulling zou plaatsen: het magazijn kent eigen bericht-ID's toe, dus
 * een tweede vulling zet alles dubbel.
 *
 * **Eén beoordeling per start, geen bewaking.** Wie tijdens een demo bewust leegt, hoort dat niet een
 * ronde later ongedaan gemaakt te zien. Een magazijn dat gevuld of al gevuld is aangetroffen, valt
 * daarom uit de rondes; alleen een magazijn dat nog niet klaar was, komt terug. Na een herstart
 * staat de post er dus wel weer.
 *
 * **Een herhaalde ronde en geen opstart-observer.** Op een verse omgeving starten alle componenten
 * tegelijk: de database van een magazijn heeft dan nog geen tabellen, of het magazijn weigert zolang
 * de profielservice er niet is. Dat wachten mag de start van de console niet ophouden. Eerst gaat er
 * één bericht heen en pas als dat aankomt de rest, zodat een magazijn dat nog niet klaar is per ronde
 * één mislukte aanlevering oplevert en niet de hele dataset.
 */
@ApplicationScoped
class MagazijnOpstartvulling(
    private val config: OpstartvullingConfig,
    private val magazijnDatabase: MagazijnDatabase,
    private val basisdataset: Basisdataset,
    private val aanleverService: AanleverService,
    private val klok: Clock,
) {

    private val log = Logger.getLogger(MagazijnOpstartvulling::class.java)

    private val gestart: Instant = klok.instant()

    /** De afzender-OIN's die deze start nog moet beoordelen; `null` tot de eerste ronde. */
    private var openstaand: MutableSet<String>? = null

    /** Een wachtend magazijn wordt één keer gemeld; op een verse preview duurt dat wachten minuten. */
    private val wachtGemeld = mutableSetOf<String>()

    // SKIP: het vullen van een magazijn kan langer duren dan het interval, en een ronde erbovenop zou
    // datzelfde nog lege magazijn een tweede keer vullen.
    @Scheduled(every = "{opstartvulling.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun tik() {
        ronde()
    }

    /** Beoordeelt elk openstaand magazijn, en zegt per afzender-OIN wat het geworden is. */
    fun ronde(): Map<String, Uitkomst> {
        val open = openstaand ?: eersteRonde().also { openstaand = it }

        if (open.isEmpty()) return emptyMap()

        if (Duration.between(gestart, klok.instant()) > config.opgevenNa()) {
            log.warnf(
                "Opstartvulling opgegeven na %s: magazijn(en) %s nog niet klaar. " +
                    "'Herstel demo' zet beide magazijnen alsnog op de basisvulling",
                config.opgevenNa(),
                open.joinToString(", "),
            )
            open.clear()

            return emptyMap()
        }

        val uitkomsten = open.associateWith { beoordeel(it) }

        open.removeAll { uitkomsten[it] !is Uitkomst.NogNietKlaar }

        return uitkomsten
    }

    private fun eersteRonde(): MutableSet<String> {
        if (!config.actief()) {
            log.info("Opstartvulling van de echte magazijnen staat uit (opstartvulling.actief)")

            return mutableSetOf()
        }

        return MagazijnDatabase.MAGAZIJN_PER_OIN.keys.toMutableSet()
    }

    private fun beoordeel(oin: String): Uitkomst {
        val aantal = try {
            magazijnDatabase.aantalVoor(oin)
        } catch (fout: Exception) {
            return wacht(oin, "database niet te lezen (${fout.message ?: fout.javaClass.simpleName})")
        }

        if (aantal > 0) {
            log.infof("Opstartvulling overgeslagen voor magazijn %s: er staan al %d berichten", oin, aantal)

            return Uitkomst.AlGevuld(aantal)
        }

        val opdrachten = basisdataset.laad().filter { it.magazijnOin == oin }

        if (opdrachten.isEmpty()) {
            log.warnf("Opstartvulling voor magazijn %s: de basisdataset heeft geen berichten voor dit magazijn", oin)

            return Uitkomst.NietsTeVullen
        }

        val eerste = aanleverService.leverAan(opdrachten.take(1))

        // De reden uit het resultaat blijft buiten deze regel: die kan de probleemmelding van het
        // magazijn bevatten, en de aanlevering heeft hem al zonder ontvanger gelogd.
        if (eerste.geslaagd == 0) return wacht(oin, "het eerste bericht kwam niet aan")

        val rest = aanleverService.leverAan(opdrachten.drop(1))
        val geslaagd = eerste.geslaagd + rest.geslaagd

        // Een ronde die deels mislukt wordt niet herhaald: wat wél aankwam, zou dan dubbel komen te staan.
        if (rest.mislukt > 0) {
            log.warnf(
                "Opstartvulling voor magazijn %s deels geplaatst: %d van %d berichten aangekomen",
                oin,
                geslaagd,
                opdrachten.size,
            )
        } else {
            log.infof("Opstartvulling geplaatst in magazijn %s: %d berichten", oin, geslaagd)
        }

        return Uitkomst.Geplaatst(aangeboden = opdrachten.size, geslaagd = geslaagd)
    }

    private fun wacht(oin: String, reden: String): Uitkomst {
        if (wachtGemeld.add(oin)) {
            log.infof("Opstartvulling voor magazijn %s wacht: %s; volgende poging over %s", oin, reden, config.interval())
        } else {
            log.debugf("Opstartvulling voor magazijn %s wacht nog: %s", oin, reden)
        }

        return Uitkomst.NogNietKlaar(reden)
    }

    sealed interface Uitkomst {

        /** Er stonden al berichten; die blijven staan. */
        data class AlGevuld(val aantal: Int) : Uitkomst

        /** De basisvulling is aangeboden; bij `geslaagd < aangeboden` volgt geen tweede poging. */
        data class Geplaatst(val aangeboden: Int, val geslaagd: Int) : Uitkomst

        /** De basisdataset heeft niets voor dit magazijn. */
        data object NietsTeVullen : Uitkomst

        /** Database of magazijn is er nog niet; de volgende ronde probeert het opnieuw. */
        data class NogNietKlaar(val reden: String) : Uitkomst
    }
}
