package nl.rijksoverheid.moz.fbs.democonsole.herstel

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.HERSTELTIJD_MELDING
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import nl.rijksoverheid.moz.fbs.democonsole.simulator.GesimuleerdHerstel
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorService
import nl.rijksoverheid.moz.fbs.democonsole.storing.StoringService
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoService
import java.util.logging.Logger

data class HerstelResultaat(
    val geleegd: Map<String, Int>,
    val vulling: AanleverResultaat,
    val gesimuleerd: GesimuleerdHerstel,
    /** Hoeveel berichten er weer in de gesimuleerde magazijnen zijn klaargezet. */
    val gesimuleerdGevuld: Int = 0,
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
        val gesimuleerd = simulatorService.herstelZoMogelijk()

        if (gesimuleerd.overgeslagen != null) return HerstelResultaat(geleegd, vulling, gesimuleerd)

        // Het vullen apart vangen: het legen is dan al gelukt, en dat terugdraaien kan niet. Zonder
        // deze melding stond de fan-out-demo op nul berichten terwijl de knop groen werd.
        val gevuld = runCatching { simulatorService.vulStandaard().berichten }.getOrElse { fout ->
            log.warning("gesimuleerde magazijnen niet gevuld: $fout")

            return HerstelResultaat(
                geleegd,
                vulling,
                gesimuleerd.copy(overgeslagen = "wel geleegd, niet gevuld: ${reden(fout)}"),
            )
        }

        return HerstelResultaat(geleegd, vulling, gesimuleerd, gevuld)
    }

    private fun reden(fout: Throwable): String = fout.message ?: fout::class.simpleName.orEmpty()
}
