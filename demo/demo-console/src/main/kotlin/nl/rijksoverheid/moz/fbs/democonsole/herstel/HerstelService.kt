package nl.rijksoverheid.moz.fbs.democonsole.herstel

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorService
import nl.rijksoverheid.moz.fbs.democonsole.storing.StoringService
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoService

data class HerstelResultaat(
    val geleegd: Map<String, Int>,
    val vulling: AanleverResultaat,
    /** Wat de gesimuleerde magazijnen kwijtraakten en hoeveel er hun gedrag terugkregen. */
    val gesimuleerd: Map<String, Int>,
    /** Hoeveel berichten er weer in de gesimuleerde magazijnen zijn klaargezet. */
    val gesimuleerdGevuld: Int,
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

    fun herstel(): HerstelResultaat {
        tempoService.stop()
        storingService.reset()

        // Ook de gesimuleerde magazijnen: berichten weg én hun gedrag terug naar de vastgelegde
        // verdeling. Zonder dat staat een magazijn dat tijdens de vorige demo op storing is gezet er
        // de volgende keer nog zo bij, en is "terug naar het begin" een halve waarheid.
        val gesimuleerd = simulatorService.herstel()
        val geleegd = magazijnDatabase.leegAlles()
        val vulling = aanleverService.leverAan(basisdataset.laad())

        // Ook de gesimuleerde magazijnen weer vullen. "Terug naar de toestand van vlak na de eerste
        // basisvulling" hoort ook voor hen te gelden; anders staat de fan-out-demo na een herstel op
        // honderd organisaties met nul berichten, en moet iemand middenin het verhaal alsnog een
        // tweede knop zoeken.
        val gesimuleerdGevuld = simulatorService.vulStandaard().berichten

        return HerstelResultaat(geleegd, vulling, gesimuleerd, gesimuleerdGevuld)
    }
}
