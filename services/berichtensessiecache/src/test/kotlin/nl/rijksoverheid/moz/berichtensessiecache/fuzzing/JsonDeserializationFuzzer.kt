package nl.rijksoverheid.moz.berichtensessiecache.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import nl.rijksoverheid.moz.berichtensessiecache.berichten.AggregationStatus
import nl.rijksoverheid.moz.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.berichtensessiecache.berichten.BerichtenPage
import nl.rijksoverheid.moz.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.berichtensessiecache.magazijn.MagazijnBerichtenResponse

object JsonDeserializationFuzzer {

    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())

    private val targetTypes = arrayOf(
        Bericht::class.java,
        MagazijnBerichtenResponse::class.java,
        MagazijnEvent::class.java,
        AggregationStatus::class.java,
        BerichtenPage::class.java,
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
            // Verwacht: ongeldige JSON of domeinvalidatie-fouten zijn geen bugs
        }
    }
}
