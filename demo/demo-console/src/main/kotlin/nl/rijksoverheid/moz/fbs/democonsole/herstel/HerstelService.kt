package nl.rijksoverheid.moz.fbs.democonsole.herstel

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import nl.rijksoverheid.moz.fbs.democonsole.storing.StoringService
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoService

data class HerstelResultaat(val geleegd: Map<String, Int>, val vulling: AanleverResultaat)

/**
 * De omgeving terug naar de toestand van vlak na de eerste basisvulling — de knop aan het eind van
 * een demo. Eén handeling, want de vier losse stappen in de verkeerde volgorde laten een halve
 * toestand achter: een lopende stroom vult tijdens het legen door, en storingen die aan blijven
 * staan laten de basisvulling mislukken.
 */
@ApplicationScoped
class HerstelService(
    private val tempoService: TempoService,
    private val storingService: StoringService,
    private val magazijnDatabase: MagazijnDatabase,
    private val basisdataset: Basisdataset,
    private val aanleverService: AanleverService,
) {

    fun herstel(): HerstelResultaat {
        tempoService.stop()
        storingService.reset()

        val geleegd = magazijnDatabase.leegAlles()
        val vulling = aanleverService.leverAan(basisdataset.laad())

        return HerstelResultaat(geleegd, vulling)
    }
}
