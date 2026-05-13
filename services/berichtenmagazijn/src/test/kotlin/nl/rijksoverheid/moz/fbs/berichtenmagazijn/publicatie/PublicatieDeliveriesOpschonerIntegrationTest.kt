package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Integratietest tegen Postgres voor de V2-cleanup view + [PublicatieDeliveriesOpschoner].
 *
 * Borgt:
 *  - `publicatie_deliveries_oud` retourneert alleen `GEPUBLICEERD`-rijen ouder
 *    dan 30 dagen — niet `MISLUKT`, niet recente.
 *  - De opschoner verwijdert die rijen daadwerkelijk en laat andere statussen
 *    intact (forensisch onderzoek vereist behoud van `MISLUKT`).
 *  - Outbox groeit niet onbeperkt onder normale werking.
 */
@QuarkusTest
@TestProfile(PublicatieDeliveriesOpschonerIntegrationTest.SoloProfile::class)
class PublicatieDeliveriesOpschonerIntegrationTest {

    @Inject
    lateinit var opschoner: PublicatieDeliveriesOpschoner

    @Inject
    lateinit var berichten: BerichtRepository

    @Inject
    lateinit var deliveries: PublicatieDeliveryRepository

    @Inject
    lateinit var entityManager: EntityManager

    @BeforeEach
    fun clean() {
        QuarkusTransaction.requiringNew().run {
            deliveries.deleteAll()
            berichten.deleteAll()
        }
    }

    private fun maakBerichtMet(): UUID {
        val nu = Instant.now()
        val b = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin("00000001003214345000"),
            ontvanger = Bsn("999993653"),
            onderwerp = "X", inhoud = "x",
            tijdstipOntvangst = nu, publicatieDatum = nu,
        )
        QuarkusTransaction.requiringNew().run {
            berichten.save(b)
        }
        return b.berichtId
    }

    private fun insertDelivery(
        berichtId: UUID,
        doel: String,
        status: String,
        gepubliceerdOp: Instant?,
    ) {
        QuarkusTransaction.requiringNew().run {
            entityManager.createNativeQuery(
                """
                INSERT INTO publicatie_deliveries
                  (bericht_id, doel, status, pogingen, volgende_poging, gepubliceerd_op, aangemaakt_op)
                VALUES (?, ?, ?, 0, ?, ?, ?)
                """.trimIndent(),
            )
                .setParameter(1, berichtId)
                .setParameter(2, doel)
                .setParameter(3, status)
                .setParameter(4, Instant.now())
                .setParameter(5, gepubliceerdOp)
                .setParameter(6, Instant.now())
                .executeUpdate()
        }
    }

    @Test
    fun `view markeert alleen GEPUBLICEERD ouder dan 30 dagen`() {
        val berichtId = maakBerichtMet()
        val oud = Instant.now().minus(31, ChronoUnit.DAYS)
        val recent = Instant.now().minus(1, ChronoUnit.DAYS)

        insertDelivery(berichtId, "oud-gepubliceerd", "GEPUBLICEERD", oud)
        insertDelivery(berichtId, "nieuw-gepubliceerd", "GEPUBLICEERD", recent)
        insertDelivery(berichtId, "oud-mislukt", "MISLUKT", null)

        val viewResultaat = QuarkusTransaction.requiringNew().call {
            entityManager.createNativeQuery(
                "SELECT doel FROM publicatie_deliveries_oud",
            ).resultList.map { it.toString() }.toSet()
        }
        assertEquals(setOf("oud-gepubliceerd"), viewResultaat)
    }

    @Test
    fun `opschoner verwijdert oude GEPUBLICEERD en laat MISLUKT en recente staan`() {
        val berichtId = maakBerichtMet()
        val oud = Instant.now().minus(31, ChronoUnit.DAYS)
        val recent = Instant.now().minus(1, ChronoUnit.DAYS)

        insertDelivery(berichtId, "oud-gepubliceerd", "GEPUBLICEERD", oud)
        insertDelivery(berichtId, "nieuw-gepubliceerd", "GEPUBLICEERD", recent)
        insertDelivery(berichtId, "oud-mislukt", "MISLUKT", null)

        opschoner.verwijderTerminaleRijen()

        val resterend = QuarkusTransaction.requiringNew().call {
            entityManager.createNativeQuery("SELECT doel FROM publicatie_deliveries")
                .resultList.map { it.toString() }.toSet()
        }
        assertTrue("oud-gepubliceerd" !in resterend, "oude GEPUBLICEERD-rij moest weg zijn: $resterend")
        assertTrue("nieuw-gepubliceerd" in resterend, "recente rij moet blijven")
        assertTrue("oud-mislukt" in resterend, "MISLUKT moet altijd blijven (forensisch)")
    }

    @Test
    fun `opschoner is no-op als view leeg is`() {
        // Geen rijen → geen DELETE; methode moet rustig terugkeren.
        opschoner.verwijderTerminaleRijen()
        assertNotNull(opschoner)
    }

    class SoloProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "magazijn.publicatie.downstreams.aanmeld.url" to "http://localhost:1/events",
            "quarkus.scheduler.enabled" to "false",
        )
    }
}
