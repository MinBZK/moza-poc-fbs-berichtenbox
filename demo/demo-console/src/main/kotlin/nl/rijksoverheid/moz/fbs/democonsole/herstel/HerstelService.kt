package nl.rijksoverheid.moz.fbs.democonsole.herstel

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.HERSTELTIJD_MELDING
import nl.rijksoverheid.moz.fbs.democonsole.sessieMelding
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import nl.rijksoverheid.moz.fbs.democonsole.sessie.SessieService
import nl.rijksoverheid.moz.fbs.democonsole.simulator.GesimuleerdHerstel
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorService
import nl.rijksoverheid.moz.fbs.democonsole.storing.StoringService
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoService
import java.util.logging.Logger

data class HerstelResultaat(
    val geleegd: Map<String, Int>,
    val vulling: AanleverResultaat,
    val gesimuleerd: GesimuleerdHerstel,
    /** Hoeveel sessie-keys er gewist zijn; `null` als dat niet lukte. */
    val sessiesGewist: Int?,
    /** Hoeveel berichten er weer in de gesimuleerde magazijnen zijn klaargezet. */
    val gesimuleerdGevuld: Int = 0,
) {

    /** Positief op de lijn: een `null` in [sessiesGewist] valt er door `non-null` af. */
    val sessiesNietGewist: Boolean = sessiesGewist == null

    /**
     * Het paneel toont één let-op-regel per antwoord; deze knop heeft er meer te melden. De reden
     * van een mislukte basisvulling voorop — daar valt iets aan te doen, aan het overslaan-venster
     * van de uitvraag niet. Bewust niet de wachtrij-melding van de vulling: na het wissen van de
     * sessies halen de berichtenboxen zelf opnieuw op, en dat gaat niet via die wachtrij.
     */
    val letOp: String = listOfNotNull(vulling.reden, sessieMelding(sessiesGewist), HERSTELTIJD_MELDING).joinToString(" ")
}

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
    private val sessieService: SessieService,
) {

    private val log = Logger.getLogger(HerstelService::class.java.name)

    fun herstel(): HerstelResultaat {
        tempoService.stop()
        storingService.reset()

        val geleegd = magazijnDatabase.leegAlles()
        val vulling = aanleverService.leverAan(basisdataset.laad())
        val (gesimuleerd, gevuld) = herstelGesimuleerde()

        // Als laatste: een berichtenbox die daarna opnieuw ophaalt, krijgt meteen de hele nieuwe set,
        // ook uit de gesimuleerde magazijnen, en niet een halve die zich pas later aanvult.
        val sessies = sessieService.laatSessiesVerlopenZoMogelijk()

        return HerstelResultaat(geleegd, vulling, gesimuleerd, sessies, gevuld)
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
    private fun herstelGesimuleerde(): Pair<GesimuleerdHerstel, Int> {
        val gesimuleerd = simulatorService.herstelZoMogelijk()

        if (gesimuleerd.overgeslagen != null) return gesimuleerd to 0

        // Het vullen apart vangen: het legen is dan al gelukt, en dat terugdraaien kan niet. Zonder
        // deze melding stond de fan-out-demo op nul berichten terwijl de knop groen werd.
        return runCatching { gesimuleerd to simulatorService.vulStandaard().berichten }.getOrElse { fout ->
            log.warning("gesimuleerde magazijnen niet gevuld: $fout")

            gesimuleerd.copy(overgeslagen = "wel geleegd, niet gevuld: ${reden(fout)}") to 0
        }
    }

    private fun reden(fout: Throwable): String = fout.message ?: fout::class.simpleName.orEmpty()
}
