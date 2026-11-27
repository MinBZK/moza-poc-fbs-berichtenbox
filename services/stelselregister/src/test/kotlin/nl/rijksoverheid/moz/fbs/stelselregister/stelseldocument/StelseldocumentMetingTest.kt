package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** De meetwaarden van een draaiende dienst, uit dezelfde registry die `/q/metrics` levert. */
@QuarkusTest
class StelseldocumentMetingTest {

    @Inject
    lateinit var registry: MeterRegistry

    @Inject
    lateinit var uitgegeven: UitgegevenStelseldocument

    @Inject
    lateinit var register: Magazijnregister

    private fun gauge(naam: String): Double = registry.get(naam).gauge().value()

    @Test
    fun `het geldende document staat met zijn tijdstippen en organisaties in de meting`() {
        val geldend = requireNotNull(uitgegeven.geldend())

        assertEquals(geldend.verlooptOp.epochSecond.toDouble(), gauge("fbs.stelseldocument.geldig.tot"))
        assertEquals(geldend.uitgegevenOp.epochSecond.toDouble(), gauge("fbs.stelseldocument.uitgegeven.op"))
        assertEquals(geldend.aantalOrganisaties.toDouble(), gauge("fbs.stelseldocument.organisaties"))
        assertEquals(register.alle().size.toDouble(), gauge("fbs.magazijnregister.magazijnen"))
    }

    // In een test ondertekent de dienst met een wegwerpketen; het label maakt dat zichtbaar.
    @Test
    fun `het verlopen van de sleutelketen ligt in de toekomst en draagt de herkomst`() {
        val tot = registry.get("fbs.stelseldocument.sleutel.geldig.tot").tag("herkomst", "wegwerp").gauge().value()

        assertTrue(tot > Instant.now().epochSecond, "keten verloopt op $tot")
    }

    @Test
    fun `zonder geldend document staan de tijdstippen op nul`() {
        val eigen = SimpleMeterRegistry()
        val zonder = mockk<UitgegevenStelseldocument> { every { geldend() } returns null }
        val leeg = mockk<Magazijnregister> { every { alle() } returns emptyList() }

        val sleutel = mockk<Ondertekensleutel> {
            every { herkomst } returns Sleutelherkomst.KEYSTORE
            every { geldigTot } returns Instant.EPOCH
        }
        val bron = mockk<Sleutelbron> { every { this@mockk.sleutel } returns sleutel }

        StelseldocumentMeting(eigen, zonder, bron, leeg).registreer(StartupEvent())

        assertEquals(0.0, eigen.get("fbs.stelseldocument.geldig.tot").gauge().value())
        assertEquals(0.0, eigen.get("fbs.stelseldocument.uitgegeven.op").gauge().value())
        assertEquals(0.0, eigen.get("fbs.stelseldocument.organisaties").gauge().value())
        assertEquals(0.0, eigen.get("fbs.magazijnregister.magazijnen").gauge().value())
    }
}
