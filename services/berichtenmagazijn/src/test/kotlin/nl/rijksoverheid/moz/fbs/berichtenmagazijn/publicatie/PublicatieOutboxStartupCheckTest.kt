package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock

/**
 * Borgt fail-closed gedrag van [PublicatieOutbox.valideerStartConfiguratie] en
 * [PublicatieOutbox.planDeliveries] bij ontbrekende downstreams. Beide moeten
 * een [IllegalStateException] gooien zodat een misconfig in productie geen
 * phantom-published berichten oplevert (opgeslagen, maar nooit gepland).
 */
class PublicatieOutboxStartupCheckTest {

    private val deliveries = mockk<PublicatieDeliveryRepository>(relaxed = true)
    private val clock: Clock = Clock.systemUTC()

    @Test
    fun `valideerStartConfiguratie faalt bij lege downstreams`() {
        val config = mockk<PublicatieConfig>()
        every { config.downstreams() } returns emptyMap()
        val outbox = PublicatieOutbox(config, deliveries, clock)

        val ex = assertThrows(IllegalStateException::class.java) {
            outbox.valideerStartConfiguratie(StartupEvent())
        }
        assertTrue(
            ex.message?.contains("downstreams") == true,
            "fout-message moet downstreams noemen, kreeg: ${ex.message}",
        )
    }

    @Test
    fun `planDeliveries faalt loud bij lege downstreams (vangnet)`() {
        val config = mockk<PublicatieConfig>()
        every { config.downstreams() } returns emptyMap()
        val outbox = PublicatieOutbox(config, deliveries, clock)

        val ex = assertThrows(IllegalStateException::class.java) {
            outbox.planDeliveries(java.util.UUID.randomUUID(), java.time.Instant.now())
        }
        assertTrue(
            ex.message?.contains("downstreams") == true,
            "fout-message moet downstreams noemen, kreeg: ${ex.message}",
        )
    }
}
