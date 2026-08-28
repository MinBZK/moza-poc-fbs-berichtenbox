package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import io.quarkus.runtime.StartupEvent
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.Gedrag
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.MagazijnRepository
import org.jboss.logging.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * De set magazijnen die deze simulator voorstelt, en de brug tussen de configuratie en de
 * database-rijen die alle andere tabellen discrimineren.
 *
 * De configuratie is de bron, niet de database: het generatiescript levert één artefact dat zowel
 * deze set als het register van de uitvraag vult, zodat beide kanten niet uit elkaar kunnen lopen.
 * Bij het starten wordt de tabel daarmee in overeenstemming gebracht.
 *
 * De database-id gaat mee in [GesimuleerdMagazijn] zodat geen enkele query hem hoeft op te zoeken;
 * bij een fan-out van honderd is dat honderd bespaarde rondjes per ophaalronde. Het gedrag reist om
 * dezelfde reden mee: elke aanroep leest het, en dat mag geen extra query kosten.
 */
@ApplicationScoped
class GesimuleerdeMagazijnen(
    private val config: MagazijnSimulatorConfig,
    private val repository: MagazijnRepository,
) {

    private val log = Logger.getLogger(GesimuleerdeMagazijnen::class.java)
    private lateinit var instellingen: Map<String, MagazijnInstelling>

    // Een gedrag dat tijdens een demo wordt bijgesteld, moet meteen gelden voor het volgende
    // verzoek — ook als dat op een andere thread binnenkomt.
    private val magazijnen = ConcurrentHashMap<String, GesimuleerdMagazijn>()

    /** De configuratie afkeuren kan zonder database, en hoort dus vóór de rest te gebeuren. */
    @PostConstruct
    fun init() {
        instellingen = MagazijnConfiguratie.valideer(config.magazijnen())
    }

    /**
     * Het observeren van [StartupEvent] doet twee dingen: het dwingt bean-instantiatie — en daarmee
     * de configuratie-validatie — af tijdens boot, en het brengt de tabel in overeenstemming vóór
     * het eerste verkeer. Zou dit lui bij de eerste request gebeuren, dan zou een fout in de
     * configuratie zich als een 404 op één magazijn voordoen in plaats van als een boot die faalt.
     */
    fun bijOpstart(@Observes startup: StartupEvent) {
        herlaad()

        log.infof(
            "Magazijn-simulator stelt %d magazijn(en) voor; gedrag: %s",
            magazijnen.size,
            magazijnen.values.groupingBy { it.gedrag.modus }.eachCount().toSortedMap(),
        )
    }

    fun voorOin(oin: String): GesimuleerdMagazijn? = magazijnen[oin]

    /**
     * Stelt het gedrag van één magazijn bij en laat dat meteen gelden. `false` als die OIN niet
     * bestaat.
     */
    fun stelGedragBij(oin: String, gedrag: Gedrag): Boolean {
        if (!repository.zetGedrag(oin, gedrag)) return false

        magazijnen.computeIfPresent(oin) { _, bestaand -> bestaand.copy(gedrag = gedrag) }

        return true
    }

    /** Zet het gedrag van alle magazijnen terug naar wat de configuratie voorschrijft. */
    fun herstelGedrag() {
        herlaad()
    }

    /** Leest de magazijn-rijen opnieuw in, zodat een bijgesteld gedrag meteen geldt. */
    fun herlaad() {
        val rijen = repository.brengInOvereenstemming(
            instellingen.mapValues { (_, instelling) ->
                MagazijnRepository.Paar(instelling.naam, instelling.gedrag)
            },
        )

        magazijnen.keys.retainAll(rijen.map { it.oin }.toSet())
        rijen.forEach { rij ->
            magazijnen[rij.oin] = GesimuleerdMagazijn(
                dbId = rij.dbId,
                oin = rij.oin,
                naam = rij.naam,
                gedrag = rij.gedrag,
            )
        }
    }
}
