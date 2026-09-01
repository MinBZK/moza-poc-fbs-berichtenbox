package nl.rijksoverheid.moz.fbs.democonsole.dataset

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import nl.rijksoverheid.moz.fbs.democonsole.generator.BijlageDto

/** Waar één bericht terechtkomt: het magazijn plus de ontvanger die het daar aantreft. */
private data class Bak(val magazijnOin: String, val ontvangerType: String, val ontvangerWaarde: String)

/**
 * Leest de curated basisdataset van het classpath (`dataset/basis.json`). Op het classpath
 * (niet in een externe map) zodat de dataset in de container-image meereist zonder mount.
 */
@ApplicationScoped
class Basisdataset(private val mapper: ObjectMapper) {

    fun laad(): List<AanleverOpdracht> {
        val inputStream = javaClass.classLoader.getResourceAsStream(PAD)
            ?: throw IllegalStateException("basisdataset niet gevonden op classpath: $PAD")

        val opdrachten: List<AanleverOpdracht> = inputStream.use { mapper.readValue(it) }

        // Wat variatie in de beginsituatie: elk derde bericht een PDF-bijlage (voor de
        // download-demo), en elk vierde alvast op gelezen (voor een realistische lees-mix).
        //
        // Geteld per berichtenbak — magazijn plus ontvanger — en niet over de vlakke lijst.
        // basis.json staat in een vaste cyclus van vier bakken, en een regel met diezelfde periode
        // raakt dan telkens dezelfde bak: "elk vierde op gelezen" zette één bak volledig op gelezen
        // en de andere drie helemaal niet. Per bak tellen maakt de mix bovendien onafhankelijk van
        // de volgorde waarin de dataset toevallig staat.
        val volgnummers = mutableMapOf<Bak, Int>()

        return opdrachten.map { opdracht ->
            val volgnummer = volgnummers.merge(opdracht.bak(), 0) { bestaand, _ -> bestaand + 1 } ?: 0
            val metBijlage = if (volgnummer % 3 == 0) {
                opdracht.metBijlage(DemoBijlage.bij(bestandsnaam(opdracht.verzoek.onderwerp)))
            } else {
                opdracht
            }

            if (volgnummer % 4 == 1) metBijlage.copy(gelezen = true) else metBijlage
        }
    }

    /** Eén berichtenbak: wat een ondernemer bij één organisatie heeft staan. */
    private fun AanleverOpdracht.bak(): Bak =
        Bak(magazijnOin, verzoek.ontvanger.type, verzoek.ontvanger.waarde)

    private fun AanleverOpdracht.metBijlage(bijlage: BijlageDto): AanleverOpdracht =
        copy(verzoek = verzoek.copy(bijlagen = listOf(bijlage)))

    private fun bestandsnaam(onderwerp: String): String =
        onderwerp.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".pdf"

    private companion object {

        const val PAD = "dataset/basis.json"
    }
}
