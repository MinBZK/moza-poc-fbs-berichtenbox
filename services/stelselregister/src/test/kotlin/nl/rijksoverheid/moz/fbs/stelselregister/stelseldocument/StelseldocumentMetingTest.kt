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
    lateinit var sleutelbron: Sleutelbron

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
    fun `het verlopen van de sleutelketen staat met zijn herkomst in de meting`() {
        val tot = registry.get("fbs.stelseldocument.sleutel.geldig.tot").tag("herkomst", "wegwerp").gauge().value()

        assertEquals(sleutelbron.sleutel.geldigTot.epochSecond.toDouble(), tot)
    }

    // Losse registry en mocks: zo is te zien dat elke scrape opnieuw leest, ook als het geldende
    // exemplaar verdwijnt of terugkomt.
    @Test
    fun `de meting volgt het geldende document bij elke scrape`() {
        val eigen = SimpleMeterRegistry()
        val uitgifte = Uitgifte(
            jws = "x.y.z",
            versie = "1",
            kid = "kid",
            uitgegevenOp = Instant.ofEpochSecond(1_000),
            verlooptOp = Instant.ofEpochSecond(2_000),
            aantalOrganisaties = 3,
        )
        val bron = mockk<UitgegevenStelseldocument> { every { geldend() } returnsMany listOf(null, null, null, uitgifte) }
        val sleutel = mockk<Ondertekensleutel> {
            every { herkomst } returns Sleutelherkomst.KEYSTORE
            every { geldigTot } returns Instant.ofEpochSecond(5_000)
        }
        val sleutels = mockk<Sleutelbron> { every { this@mockk.sleutel } returns sleutel }
        val leeg = mockk<Magazijnregister> { every { alle() } returns emptyList() }

        StelseldocumentMeting(eigen, bron, sleutels, leeg).registreer(StartupEvent())

        val waarde = { naam: String -> eigen.get(naam).gauge().value() }

        assertEquals(0.0, waarde("fbs.stelseldocument.geldig.tot"))
        assertEquals(0.0, waarde("fbs.stelseldocument.uitgegeven.op"))
        assertEquals(0.0, waarde("fbs.stelseldocument.organisaties"))
        assertEquals(2_000.0, waarde("fbs.stelseldocument.geldig.tot"))
        assertEquals(1_000.0, waarde("fbs.stelseldocument.uitgegeven.op"))
        assertEquals(3.0, waarde("fbs.stelseldocument.organisaties"))
        assertEquals(5_000.0, eigen.get("fbs.stelseldocument.sleutel.geldig.tot").tag("herkomst", "keystore").gauge().value())
        assertEquals(0.0, waarde("fbs.magazijnregister.magazijnen"))
    }
}
