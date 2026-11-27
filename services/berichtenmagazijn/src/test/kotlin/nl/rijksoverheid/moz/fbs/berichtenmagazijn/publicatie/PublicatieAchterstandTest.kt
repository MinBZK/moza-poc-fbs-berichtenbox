package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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
        PublicatieAchterstand.Rij(status, doel, aantal, nu.minusSeconds(secondenOud))

    @Test
    fun `elke combinatie van status en bestemming bestaat vanaf de start, op nul`() {
        val reeksen = registry.find("fbs.publicatie.achterstand").gauges()

        // twee statussen × (twee bestemmingen + _onbekend)
        assertEquals(6, reeksen.size)
        reeksen.forEach { assertEquals(0.0, it.value()) }
        assertEquals(0.0, registry.get("fbs.publicatie.achterstand.gemeten.op").gauge().value())
    }

    @Test
    fun `de telling komt per status en bestemming in de meting`() {
        achterstand.zet(
            listOf(
                rij(DeliveryStatus.TE_PUBLICEREN, "aanmeld", 3, 120),
                rij(DeliveryStatus.TE_PUBLICEREN, "notificatie", 1, 5),
                rij(DeliveryStatus.MISLUKT, "notificatie", 2, 7200),
            ),
            nu,
        )

        assertEquals(3.0, aantal("te_publiceren", "aanmeld"))
        assertEquals(120.0, oudste("te_publiceren", "aanmeld"))
        assertEquals(1.0, aantal("te_publiceren", "notificatie"))
        assertEquals(2.0, aantal("mislukt", "notificatie"))
        assertEquals(7200.0, oudste("mislukt", "notificatie"))
        assertEquals(0.0, aantal("mislukt", "aanmeld"))
        assertEquals(nu.epochSecond.toDouble(), registry.get("fbs.publicatie.achterstand.gemeten.op").gauge().value())
    }

    // Twee vervallen bestemmingen komen samen onder één label: opgeteld, met de oudste van beide.
    @Test
    fun `rijen met een vervallen bestemming tellen samen als onbekend`() {
        achterstand.zet(
            listOf(
                rij(DeliveryStatus.TE_PUBLICEREN, "oud-a", 2, 60),
                rij(DeliveryStatus.TE_PUBLICEREN, "oud-b", 4, 600),
            ),
            nu,
        )

        assertEquals(6.0, aantal("te_publiceren", DownstreamClient.ONBEKEND_DOEL))
        assertEquals(600.0, oudste("te_publiceren", DownstreamClient.ONBEKEND_DOEL))
        assertEquals(6, registry.find("fbs.publicatie.achterstand").gauges().size)
    }

    @Test
    fun `een opgeloste achterstand gaat terug naar nul`() {
        achterstand.zet(listOf(rij(DeliveryStatus.TE_PUBLICEREN, "aanmeld", 3, 120)), nu)
        achterstand.zet(emptyList(), nu)

        assertEquals(0.0, aantal("te_publiceren", "aanmeld"))
        assertEquals(0.0, oudste("te_publiceren", "aanmeld"))
    }

    // Een klok die achterloopt op de database geeft geen negatieve leeftijd.
    @ParameterizedTest
    @ValueSource(longs = [-30, 0])
    fun `een aanmelding uit de toekomst of van nu is nul seconden oud`(secondenOud: Long) {
        achterstand.zet(listOf(rij(DeliveryStatus.TE_PUBLICEREN, "aanmeld", 1, secondenOud)), nu)

        assertEquals(0.0, oudste("te_publiceren", "aanmeld"))
    }

    @Test
    fun `een mislukte telling laat de vorige waarden staan`() {
        achterstand.zet(listOf(rij(DeliveryStatus.TE_PUBLICEREN, "aanmeld", 3, 120)), nu.minusSeconds(30))
        every { entityManager.createNativeQuery(any<String>()) } throws PersistenceException("database weg")

        achterstand.tel()

        assertEquals(3.0, aantal("te_publiceren", "aanmeld"))
        assertEquals(
            nu.minusSeconds(30).epochSecond.toDouble(),
            registry.get("fbs.publicatie.achterstand.gemeten.op").gauge().value(),
        )
    }
}
