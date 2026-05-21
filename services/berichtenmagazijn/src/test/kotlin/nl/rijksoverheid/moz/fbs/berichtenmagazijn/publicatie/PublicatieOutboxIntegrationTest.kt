package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
@TestProfile(PublicatieOutboxIntegrationTest.TweeDownstreamsProfile::class)
class PublicatieOutboxIntegrationTest {

    @Inject
    lateinit var outbox: PublicatieOutbox

    @Inject
    lateinit var berichten: BerichtRepository

    @Inject
    lateinit var deliveries: PublicatieDeliveryRepository

    @BeforeEach
    @Transactional
    fun clean() {
        // CASCADE op `publicatie_deliveries.bericht_db_id` ruimt deliveries op via
        // berichten.deleteAll(). Expliciet eerst zelf om volgorde te garanderen
        // bij wijzigingen in de cascade-instelling.
        deliveries.deleteAll()
        berichten.deleteAll()
    }

    @Test
    @Transactional
    fun `planDeliveries maakt een rij per geconfigureerde downstream`() {
        val tijdstip = Instant.parse("2026-05-12T10:00:00Z")
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin("00000001003214345000"),
            ontvanger = Bsn("999993653"),
            onderwerp = "Test outbox",
            inhoud = "Inhoud",
            tijdstipOntvangst = tijdstip,
            publicatiedatum = tijdstip,
        )
        berichten.save(bericht)

        outbox.planDeliveries(bericht.berichtId, bericht.publicatiedatum)

        val rijen = deliveries.findByBerichtId(bericht.berichtId)
        // Filter de globale `default`-downstream uit `src/test/resources/application.properties`
        // weg; deze test borgt alleen het profile-level gedrag voor aanmeld + notificatie.
        val rijenZonderDefault = rijen.filter { it.doel != "default" }
        assertEquals(2, rijenZonderDefault.size, "verwacht 2 deliveries (aanmeld + notificatie)")
        val doelen = rijenZonderDefault.map { it.doel }.toSet()
        assertEquals(setOf("aanmeld", "notificatie"), doelen)
        assertTrue(rijenZonderDefault.all { it.status == DeliveryStatus.TE_PUBLICEREN })
        assertTrue(rijenZonderDefault.all { it.pogingen == 0 })
        assertTrue(rijenZonderDefault.all { it.volgendePoging == tijdstip })
    }

    @Test
    @Transactional
    fun `planDeliveries met uitgestelde publicatiedatum zet volgendePoging op die datum`() {
        val tijdstip = Instant.parse("2026-05-12T10:00:00Z")
        val toekomst = Instant.parse("2026-12-31T08:00:00Z")
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin("00000001003214345000"),
            ontvanger = Bsn("999993653"),
            onderwerp = "Uitgesteld",
            inhoud = "Inhoud",
            tijdstipOntvangst = tijdstip,
            publicatiedatum = toekomst,
        )
        berichten.save(bericht)

        outbox.planDeliveries(bericht.berichtId, toekomst)

        val rijen = deliveries.findByBerichtId(bericht.berichtId)
        assertTrue(rijen.all { it.volgendePoging == toekomst })
    }

    class TweeDownstreamsProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "magazijn.publicatie.downstreams.aanmeld.url" to "http://localhost:1/events",
            "magazijn.publicatie.downstreams.notificatie.url" to "http://localhost:2/events",
            // Geen scheduler in deze tests; we testen alleen het schrijfpad.
            "quarkus.scheduler.enabled" to "false",
        )
    }
}
