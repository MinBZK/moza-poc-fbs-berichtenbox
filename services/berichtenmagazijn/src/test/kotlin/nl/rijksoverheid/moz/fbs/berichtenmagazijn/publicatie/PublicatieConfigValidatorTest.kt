package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit-tests voor [PublicatieConfigValidator]: borgt dat de cross-veld- en
 * positief-invarianten op de `Duration`-config bij boot falen i.p.v. pas bij
 * de eerste retry/pollronde.
 */
class PublicatieConfigValidatorTest {

    private fun downstream(basis: Duration, plafond: Duration): PublicatieConfig.Downstream {
        val backoff = mockk<PublicatieConfig.Backoff> {
            every { basis() } returns basis
            every { plafond() } returns plafond
        }
        return mockk<PublicatieConfig.Downstream> {
            every { backoff() } returns backoff
        }
    }

    private fun config(
        pollingInterval: Duration = Duration.ofSeconds(60),
        opschonenInterval: Duration = Duration.ofHours(24),
        downstreams: Map<String, PublicatieConfig.Downstream> =
            mapOf("aanmeld" to downstream(Duration.ofSeconds(1), Duration.ofHours(1))),
    ): PublicatieConfig = mockk {
        every { polling() } returns mockk { every { interval() } returns pollingInterval }
        every { opschonen() } returns mockk { every { interval() } returns opschonenInterval }
        every { downstreams() } returns downstreams
    }

    @Test
    fun `geldige config passeert`() {
        assertDoesNotThrow {
            PublicatieConfigValidator(config()).valideer(StartupEvent())
        }
    }

    @Test
    fun `plafond kleiner dan basis faalt`() {
        val cfg = config(
            downstreams = mapOf("aanmeld" to downstream(Duration.ofMinutes(1), Duration.ofSeconds(10))),
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            PublicatieConfigValidator(cfg).valideer(StartupEvent())
        }
        assertTrue(ex.message!!.contains("plafond"), "verwacht plafond-referentie, kreeg: ${ex.message}")
    }

    @Test
    fun `niet-positieve backoff-basis faalt`() {
        val cfg = config(
            downstreams = mapOf("aanmeld" to downstream(Duration.ZERO, Duration.ofHours(1))),
        )
        assertThrows(IllegalStateException::class.java) {
            PublicatieConfigValidator(cfg).valideer(StartupEvent())
        }
    }

    @Test
    fun `niet-positief polling-interval faalt`() {
        val cfg = config(pollingInterval = Duration.ofSeconds(-1))
        assertThrows(IllegalStateException::class.java) {
            PublicatieConfigValidator(cfg).valideer(StartupEvent())
        }
    }
}
