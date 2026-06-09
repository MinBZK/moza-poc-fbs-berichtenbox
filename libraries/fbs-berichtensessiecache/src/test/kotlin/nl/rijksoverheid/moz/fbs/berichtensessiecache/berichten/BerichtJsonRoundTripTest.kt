package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.rijksoverheid.moz.fbs.common.identificatie.Rsin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Borgt dat de veld-niveau Jackson-(de)serializer op [Bericht.ontvanger] — gebruikt voor de
 * sessie-lijst-blob — het type behoudt via de canonieke `"TYPE:waarde"`-string. Dit is het
 * pad dat `store`/`getPage` gebruiken; reconstructie is self-contained (geen request-context).
 */
class BerichtJsonRoundTripTest {

    // findAndRegisterModules: naast de Kotlin-module ook de JSR-310-module (Instant) laden,
    // net als de door Quarkus geconfigureerde ObjectMapper die de cache in productie gebruikt.
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    private val bericht = Bericht(
        berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        afzender = "00000001234567890000",
        ontvanger = Rsin("999993653"),
        onderwerp = "Test",
        inhoud = "Inhoud",
        publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
        magazijnId = "magazijn-a",
        aantalBijlagen = 0,
    )

    @Test
    fun `ontvanger serialiseert als canonieke TYPE-waarde string in de blob`() {
        val json = mapper.writeValueAsString(bericht)
        assertTrue(json.contains("\"RSIN:999993653\""), "Was: $json")
    }

    @Test
    fun `round-trip behoudt het ontvanger-type`() {
        val terug = mapper.readValue(mapper.writeValueAsString(bericht), Bericht::class.java)
        assertEquals(Rsin("999993653"), terug.ontvanger)
    }
}
