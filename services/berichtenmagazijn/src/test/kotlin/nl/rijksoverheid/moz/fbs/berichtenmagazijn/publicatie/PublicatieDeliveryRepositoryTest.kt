package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Borgt dat [PublicatieDeliveryRepository.planNieuwe] luid faalt als het parent-bericht
 * ontbreekt — geen stille no-op die een phantom-delivery (zonder parent) zou verbergen.
 * Pure unit test: de [IllegalStateException] valt vóór `persist()`, dus geen CDI/EM nodig.
 */
class PublicatieDeliveryRepositoryTest {

    private val berichtRepository = mockk<BerichtRepository>()
    private val repository = PublicatieDeliveryRepository(berichtRepository)

    @Test
    fun `planNieuwe gooit ISE met berichtId wanneer parent-bericht ontbreekt`() {
        val berichtId = UUID.randomUUID()
        every { berichtRepository.findEntityByBerichtId(berichtId) } returns null
        val nu = Instant.parse("2026-05-12T10:00:00Z")

        val ex = assertThrows(IllegalStateException::class.java) {
            repository.planNieuwe(
                berichtId = berichtId,
                doel = Publicatiedoel("aanmeld"),
                volgendePoging = nu,
                aangemaaktOp = nu,
            )
        }
        assertTrue(
            ex.message?.contains(berichtId.toString()) == true,
            "fout-message moet berichtId noemen voor correlatie, kreeg: ${ex.message}",
        )
    }
}
