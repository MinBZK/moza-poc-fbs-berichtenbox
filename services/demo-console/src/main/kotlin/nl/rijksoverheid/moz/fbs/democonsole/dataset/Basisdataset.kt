package nl.rijksoverheid.moz.fbs.democonsole.dataset

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht

/**
 * Leest de curated basisdataset van het classpath (`dataset/basis.json`). Op het classpath
 * (niet in een externe map) zodat de dataset in de container-image meereist zonder mount.
 */
@ApplicationScoped
class Basisdataset(private val mapper: ObjectMapper) {

    fun laad(): List<AanleverOpdracht> {
        val stroom = javaClass.classLoader.getResourceAsStream(PAD)
            ?: throw IllegalStateException("basisdataset niet gevonden op classpath: $PAD")

        return stroom.use { mapper.readValue(it) }
    }

    private companion object {

        const val PAD = "dataset/basis.json"
    }
}
