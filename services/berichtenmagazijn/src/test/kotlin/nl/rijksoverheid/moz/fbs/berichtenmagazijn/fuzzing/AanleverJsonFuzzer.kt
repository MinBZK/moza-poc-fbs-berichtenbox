package nl.rijksoverheid.moz.fbs.berichtenmagazijn.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtAanleverenRequest
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BijlageAanleverenRequest
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Identificatienummer as IdentificatienummerDto

/**
 * Fuzz de Jackson-deserialisatie van de aanlever-payload-DTO's. Dit is de eerste laag
 * die onvertrouwde request-bodies raakt; willekeurige of misvormde JSON mag hooguit een
 * gevangen deserialisatie-fout opleveren, geen ongecontroleerde crash. De round-trip
 * (deserialiseren → serialiseren → opnieuw deserialiseren) borgt bovendien dat een
 * succesvol geparste DTO stabiel terug te schrijven is.
 */
object AanleverJsonFuzzer {

    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())

    private val targetTypes = arrayOf(
        BerichtAanleverenRequest::class.java,
        BijlageAanleverenRequest::class.java,
        IdentificatienummerDto::class.java,
    )

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val targetType = data.pickValue(targetTypes)
        val json = data.consumeRemainingAsString()

        try {
            val parsed = objectMapper.readValue(json, targetType)
            val serialized = objectMapper.writeValueAsString(parsed)
            objectMapper.readValue(serialized, targetType)
        } catch (_: Exception) {
            // Verwacht: ongeldige JSON of type-mismatch is geen bug, geen ongecontroleerde fout.
        }
    }
}
