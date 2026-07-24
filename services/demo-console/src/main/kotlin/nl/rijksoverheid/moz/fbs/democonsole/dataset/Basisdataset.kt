package nl.rijksoverheid.moz.fbs.democonsole.dataset

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import nl.rijksoverheid.moz.fbs.democonsole.generator.BijlageDto

/**
 * Leest de curated basisdataset van het classpath (`dataset/basis.json`). Op het classpath
 * (niet in een externe map) zodat de dataset in de container-image meereist zonder mount.
 */
@ApplicationScoped
class Basisdataset(private val mapper: ObjectMapper) {

    fun laad(): List<AanleverOpdracht> {
        val stroom = javaClass.classLoader.getResourceAsStream(PAD)
            ?: throw IllegalStateException("basisdataset niet gevonden op classpath: $PAD")

        val opdrachten: List<AanleverOpdracht> = stroom.use { mapper.readValue(it) }

        // Wat variatie in de beginsituatie: elk derde bericht een PDF-bijlage (voor de
        // download-demo), en elk vierde alvast op gelezen (voor een realistische lees-mix).
        return opdrachten.mapIndexed { index, opdracht ->
            val metBijlage = if (index % 3 == 0) opdracht.metBijlage(DemoBijlage.bij(bestandsnaam(opdracht.verzoek.onderwerp))) else opdracht

            if (index % 4 == 1) metBijlage.copy(gelezen = true) else metBijlage
        }
    }

    private fun AanleverOpdracht.metBijlage(bijlage: BijlageDto): AanleverOpdracht =
        copy(verzoek = verzoek.copy(bijlagen = listOf(bijlage)))

    private fun bestandsnaam(onderwerp: String): String =
        onderwerp.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".pdf"

    private companion object {

        const val PAD = "dataset/basis.json"
    }
}
