package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import io.quarkus.runtime.StartupEvent
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.MagazijnRepository
import org.jboss.logging.Logger

/**
 * De set magazijnen die deze simulator voorstelt, en de brug tussen de configuratie en de
 * database-rijen die alle andere tabellen discrimineren.
 *
 * De configuratie is de bron, niet de database: het generatiescript levert één artefact dat zowel
 * deze set als het register van de uitvraag vult, zodat beide kanten niet uit elkaar kunnen lopen.
 * Bij het starten wordt de tabel daarmee in overeenstemming gebracht.
 *
 * De database-id gaat mee in [GesimuleerdMagazijn] zodat geen enkele query hem hoeft op te zoeken;
 * bij een fan-out van honderd is dat honderd bespaarde rondjes per ophaalronde.
 */
@ApplicationScoped
class GesimuleerdeMagazijnen(
    private val config: MagazijnSimulatorConfig,
    private val repository: MagazijnRepository,
) {

    private val log = Logger.getLogger(GesimuleerdeMagazijnen::class.java)
    private lateinit var naamPerOin: Map<String, String>
    private lateinit var magazijnen: Map<String, GesimuleerdMagazijn>

    /** De configuratie afkeuren kan zonder database, en hoort dus vóór de rest te gebeuren. */
    @PostConstruct
    fun init() {
        naamPerOin = MagazijnConfiguratie.valideer(config.magazijnen())
    }

    /**
     * Het observeren van [StartupEvent] doet twee dingen: het dwingt bean-instantiatie — en daarmee
     * de configuratie-validatie — af tijdens boot, en het brengt de tabel in overeenstemming vóór
     * het eerste verkeer. Zou dit lui bij de eerste request gebeuren, dan zou een fout in de
     * configuratie zich als een 404 op één magazijn voordoen in plaats van als een boot die faalt.
     */
    fun bijOpstart(@Observes startup: StartupEvent) {
        val dbIdPerOin = repository.brengInOvereenstemming(naamPerOin)

        magazijnen = naamPerOin.mapValues { (oin, naam) ->
            GesimuleerdMagazijn(
                dbId = checkNotNull(dbIdPerOin[oin]) { "Magazijn $oin is niet aangemaakt in de database" },
                oin = oin,
                naam = naam,
            )
        }

        log.infof("Magazijn-simulator stelt %d magazijn(en) voor", magazijnen.size)
    }

    fun voorOin(oin: String): GesimuleerdMagazijn? = magazijnen[oin]

    /** Alle magazijnen, voor code die de hele set langsgaat in plaats van er één te kiezen. */
    fun alle(): Collection<GesimuleerdMagazijn> = magazijnen.values
}
