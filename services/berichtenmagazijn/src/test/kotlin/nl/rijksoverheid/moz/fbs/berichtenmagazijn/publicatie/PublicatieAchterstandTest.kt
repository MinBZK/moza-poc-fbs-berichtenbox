package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.narayana.jta.QuarkusTransactionException
import io.quarkus.runtime.StartupEvent
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class PublicatieAchterstandTest {

    private val nu = Instant.parse("2026-11-28T12:00:00Z")

    private val registry = SimpleMeterRegistry()

    private val entityManager = mockk<EntityManager>()

    private val config = mockk<PublicatieConfig> {
        every { downstreams() } returns mapOf("aanmeld" to mockk(), "notificatie" to mockk())
    }

    private val achterstand = PublicatieAchterstand(registry, entityManager, config, Clock.fixed(nu, ZoneOffset.UTC))
        .also { it.registreer(StartupEvent()) }

    private fun aantal(status: String, doel: String) =
        registry.get("fbs.publicatie.achterstand").tags("status", status, "doel", doel).gauge().value()

    private fun oudste(status: String, doel: String) =
        registry.get("fbs.publicatie.achterstand.oudste").tags("status", status, "doel", doel).gauge().value()

    private fun rij(status: DeliveryStatus, doel: String, aantal: Long, secondenOud: Long) =
        PublicatieAchterstand.Rij(status, doel, aantal, secondenOud)

    private fun gemetenOp() = registry.get("fbs.publicatie.achterstand.gemeten.op").gauge().value()

    @Test
    fun `elke combinatie van status en bestemming bestaat vanaf de start, op nul`() {
        val reeksen = registry.find("fbs.publicatie.achterstand").gauges()

        // twee statussen × (twee bestemmingen + _onbekend); de leeftijd alleen voor te_publiceren
        assertEquals(6, reeksen.size)
        assertEquals(3, registry.find("fbs.publicatie.achterstand.oudste").gauges().size)
        reeksen.forEach { assertEquals(0.0, it.value()) }
        assertEquals(0.0, gemetenOp())
    }

    @Test
    fun `de telling komt per status en bestemming in de meting`() {
        achterstand.zet(
            listOf(
                rij(DeliveryStatus.TE_PUBLICEREN, "aanmeld", 3, 120),
                rij(DeliveryStatus.TE_PUBLICEREN, "notificatie", 1, 5),
                rij(DeliveryStatus.MISLUKT, "notificatie", 2, 7200),
            ),
        )

        assertEquals(3.0, aantal("te_publiceren", "aanmeld"))
        assertEquals(120.0, oudste("te_publiceren", "aanmeld"))
        assertEquals(1.0, aantal("te_publiceren", "notificatie"))
        assertEquals(5.0, oudste("te_publiceren", "notificatie"))
        assertEquals(2.0, aantal("mislukt", "notificatie"))
        assertEquals(0.0, aantal("mislukt", "aanmeld"))
        assertEquals(nu.epochSecond.toDouble(), gemetenOp())
    }

    @Test
    fun `de leeftijd van mislukte rijen telt niet mee`() {
        achterstand.zet(listOf(rij(DeliveryStatus.MISLUKT, "aanmeld", 1, 7200)))

        assertEquals(0.0, oudste("te_publiceren", "aanmeld"))
        assertEquals(null, registry.find("fbs.publicatie.achterstand.oudste").tag("status", "mislukt").gauge())
    }

    // Twee vervallen bestemmingen komen samen onder één label: opgeteld, met de oudste van beide.
    @Test
    fun `rijen met een vervallen bestemming tellen samen als onbekend`() {
        achterstand.zet(
            listOf(
                rij(DeliveryStatus.TE_PUBLICEREN, "oud-a", 2, 60),
                rij(DeliveryStatus.TE_PUBLICEREN, "oud-b", 4, 600),
            ),
        )

        assertEquals(6.0, aantal("te_publiceren", DownstreamClient.ONBEKEND_DOEL))
        assertEquals(600.0, oudste("te_publiceren", DownstreamClient.ONBEKEND_DOEL))
        assertEquals(6, registry.find("fbs.publicatie.achterstand").gauges().size)
    }

    @Test
    fun `een opgeloste achterstand gaat terug naar nul`() {
        achterstand.zet(listOf(rij(DeliveryStatus.TE_PUBLICEREN, "aanmeld", 3, 120)))
        achterstand.zet(emptyList())

        assertEquals(0.0, aantal("te_publiceren", "aanmeld"))
        assertEquals(0.0, oudste("te_publiceren", "aanmeld"))
    }

    @ParameterizedTest
    @MethodSource("fouten")
    fun `een mislukte telling laat de vorige waarden staan`(fout: RuntimeException) {
        achterstand.tel { listOf(rij(DeliveryStatus.TE_PUBLICEREN, "aanmeld", 3, 120)) }
        val gemeten = gemetenOp()

        achterstand.tel { throw fout }

        assertEquals(3.0, aantal("te_publiceren", "aanmeld"))
        assertEquals(gemeten, gemetenOp())
    }

    @Test
    fun `een andere fout gaat door naar de scheduler`() {
        assertThrows<IllegalArgumentException> { achterstand.tel { throw IllegalArgumentException("programmeerfout") } }
    }

    @Test
    fun `definitief mislukte aanmeldingen tellen per bestemming, vanaf nul`() {
        val teller = { doel: String -> registry.get("fbs.publicatie.mislukt").tag("doel", doel).counter().count() }

        assertEquals(0.0, teller("aanmeld"))
        assertEquals(0.0, teller(DownstreamClient.ONBEKEND_DOEL))

        achterstand.definitiefMislukt("aanmeld")
        achterstand.definitiefMislukt("aanmeld")
        achterstand.definitiefMislukt("notificatie")

        assertEquals(2.0, teller("aanmeld"))
        assertEquals(1.0, teller("notificatie"))
    }

    // Een vervallen bestemming maakt geen eigen reeks aan.
    @Test
    fun `een onbekende bestemming telt als onbekend`() {
        achterstand.definitiefMislukt("oud-a")
        achterstand.definitiefMislukt("oud-b")

        assertEquals(2.0, registry.get("fbs.publicatie.mislukt").tag("doel", DownstreamClient.ONBEKEND_DOEL).counter().count())
        assertEquals(3, registry.find("fbs.publicatie.mislukt").counters().size)
    }

    companion object {
        @JvmStatic
        fun fouten(): List<RuntimeException> = listOf(
            PersistenceException("database weg"),
            QuarkusTransactionException(IllegalStateException("rollback")),
            IllegalStateException("EntityManager gesloten"),
        )
    }
}
