package nl.rijksoverheid.moz.fbs.democonsole.herstel

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.HERSTELTIJD_MELDING
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import nl.rijksoverheid.moz.fbs.democonsole.omgeving.OmgevingConfig
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorService
import nl.rijksoverheid.moz.fbs.democonsole.storing.StoringService
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoService
import java.util.logging.Logger

data class HerstelResultaat(
    val geleegd: Map<String, Int>,
    val vulling: AanleverResultaat,
    /**
     * `berichten` = hoeveel er uit de gesimuleerde magazijnen weg zijn, `magazijnen` = hoeveel er
     * hun vastgelegde gedrag terugkregen. Leeg wanneer ze zijn overgeslagen.
     */
    val gesimuleerd: Map<String, Int> = emptyMap(),
    /** Hoeveel berichten er weer in de gesimuleerde magazijnen zijn klaargezet. */
    val gesimuleerdGevuld: Int = 0,
    /** Waarom de gesimuleerde magazijnen zijn overgeslagen; `null` als ze meegingen. */
    val gesimuleerdOvergeslagen: String? = null,
    val letOp: String = HERSTELTIJD_MELDING,
)

/**
 * De omgeving terug naar de toestand van vlak na de eerste basisvulling — de knop aan het eind van
 * een demo. Eén handeling, want de losse stappen in de verkeerde volgorde laten een halve toestand
 * achter: een lopende stroom vult tijdens het legen door, en storingen die aan blijven staan laten
 * de basisvulling mislukken.
 */
@ApplicationScoped
class HerstelService(
    private val tempoService: TempoService,
    private val storingService: StoringService,
    private val magazijnDatabase: MagazijnDatabase,
    private val basisdataset: Basisdataset,
    private val aanleverService: AanleverService,
    private val simulatorService: SimulatorService,
    private val omgeving: OmgevingConfig,
) {

    private val log = Logger.getLogger(HerstelService::class.java.name)

    fun herstel(): HerstelResultaat {
        tempoService.stop()
        storingService.reset()

        val geleegd = magazijnDatabase.leegAlles()
        val vulling = aanleverService.leverAan(basisdataset.laad())

        return metGesimuleerde(geleegd, vulling)
    }

    /**
     * De gesimuleerde magazijnen horen erbij — anders staat de fan-out-demo na een herstel op
     * honderd organisaties met nul berichten, en staat een magazijn dat vorige keer op storing werd
     * gezet er nog zo bij.
     *
     * Maar ze komen ná de twee echte magazijnen en ze mogen het herstel niet tegenhouden. Een
     * simulator die er niet is of niet antwoordt, staat het legen en vullen van A en B nergens in
     * de weg; hem eerst aanroepen liet die twee ongemoeid en de omgeving halverwege staan, met de
     * berichten van de vorige demo er nog in.
     */
    private fun metGesimuleerde(geleegd: Map<String, Int>, vulling: AanleverResultaat): HerstelResultaat {
        if (!omgeving.simulator()) {
            return HerstelResultaat(
                geleegd,
                vulling,
                gesimuleerdOvergeslagen = "deze omgeving kent geen magazijn-simulator",
            )
        }

        // Overslaan is hier een uitkomst en geen fout: het herstel zelf is al gelukt, en dat als
        // mislukt melden zou de bediener een tweede keer laten legen en vullen. Het paneel toont de
        // reden bij de knop, dus stil is dit niet.
        return try {
            val gesimuleerd = simulatorService.herstel()

            HerstelResultaat(geleegd, vulling, gesimuleerd, simulatorService.vulStandaard().berichten)
        } catch (fout: Exception) {
            log.warning("gesimuleerde magazijnen niet hersteld: $fout")

            HerstelResultaat(
                geleegd,
                vulling,
                gesimuleerdOvergeslagen = fout.message ?: fout::class.simpleName.orEmpty(),
            )
        }
    }
}
