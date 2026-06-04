package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Integratietest tegen Quarkus Dev Services Postgres. Borgt het claim/markeer-
 * gedrag van [PublicatieClaimer] (Postgres-implementatie).
 */
@QuarkusTest
@TestProfile(PostgresPublicatieClaimerIntegrationTest.SoloDownstreamProfile::class)
class PostgresPublicatieClaimerIntegrationTest {

    @Inject
    lateinit var claimer: PublicatieClaimer

    @Inject
    lateinit var berichten: BerichtRepository

    @Inject
    lateinit var deliveries: PublicatieDeliveryRepository

    @Inject
    lateinit var outbox: PublicatieOutbox

    @BeforeEach
    @Transactional
    fun clean() {
        deliveries.deleteAll()
        berichten.deleteAll()
    }

    private fun maakBericht(publicatietijdstip: Instant): UUID {
        val tijdstip = Instant.parse("2026-05-12T10:00:00Z")
        val b = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin("00000001003214345000"),
            ontvanger = Bsn("999993653"),
            onderwerp = "Test",
            inhoud = "Inhoud",
            tijdstipOntvangst = tijdstip,
            publicatietijdstip = publicatietijdstip,
        )
        berichten.save(b)
        outbox.planDeliveries(b.berichtId, publicatietijdstip)
        return b.berichtId
    }

    @Test
    @Transactional
    fun `claimNuVerwerkbaar levert rijen met verstreken volgende_poging`() {
        val verleden = Instant.now().minusSeconds(60)
        val berichtId = maakBericht(verleden)

        val claims = claimer.claimNuVerwerkbaar(maxBatch = 10)
        assertTrue(
            claims.any { it.berichtId == berichtId },
            "verwacht claim voor zojuist geplande bericht $berichtId; kreeg ${claims.map { it.berichtId }}",
        )
    }

    @Test
    @Transactional
    fun `claimNuVerwerkbaar levert geen rijen met toekomstige volgende_poging`() {
        val verreToekomst = Instant.now().plusSeconds(86_400)
        val berichtId = maakBericht(verreToekomst)

        val claims = claimer.claimNuVerwerkbaar(maxBatch = 10)
        assertTrue(
            claims.none { it.berichtId == berichtId },
            "verwacht GEEN claim voor toekomstige bericht $berichtId",
        )
    }

    @Test
    @Transactional
    fun `markeerGeslaagd zet status op GEPUBLICEERD en gepubliceerd_op`() {
        val nu = Instant.now()
        val berichtId = maakBericht(nu.minusSeconds(60))
        val claim = claimer.claimNuVerwerkbaar(maxBatch = 10).first { it.berichtId == berichtId }

        claimer.markeerGeslaagd(claim.claimId, nu)

        val rij = deliveries.findById(claim.claimId)!!
        assertEquals(DeliveryStatus.GEPUBLICEERD, rij.status)
        assertNotNull(rij.gepubliceerdOp)
    }

    @Test
    @Transactional
    fun `markeerMislukt met volgendePoging schedulet retry, status blijft TE_PUBLICEREN`() {
        val nu = Instant.now()
        val berichtId = maakBericht(nu.minusSeconds(60))
        val claim = claimer.claimNuVerwerkbaar(maxBatch = 10).first { it.berichtId == berichtId }

        val later = nu.plusSeconds(30)
        claimer.markeerMislukt(claim.claimId, "test-fout", volgendePoging = later)

        val rij = deliveries.findById(claim.claimId)!!
        assertEquals(DeliveryStatus.TE_PUBLICEREN, rij.status)
        assertEquals(1, rij.pogingen)
        assertEquals(later, rij.volgendePoging)
        assertEquals("test-fout", rij.laatsteFout)
    }

    @Test
    @Transactional
    fun `markeerMislukt zonder volgendePoging zet status op MISLUKT`() {
        val nu = Instant.now()
        val berichtId = maakBericht(nu.minusSeconds(60))
        val claim = claimer.claimNuVerwerkbaar(maxBatch = 10).first { it.berichtId == berichtId }

        claimer.markeerMislukt(claim.claimId, "definitief mislukt", volgendePoging = null)

        val rij = deliveries.findById(claim.claimId)!!
        assertEquals(DeliveryStatus.MISLUKT, rij.status)
        assertEquals(1, rij.pogingen)
        assertEquals("definitief mislukt", rij.laatsteFout)
    }

    @Test
    @Transactional
    fun `MISLUKT en GEPUBLICEERD rijen worden niet meer geclaimd`() {
        // Borgt dat de partial index `WHERE status = 'TE_PUBLICEREN'` correct werkt:
        // terminal-status rijen vallen uit de claim-query.
        val nu = Instant.now()
        val berichtId = maakBericht(nu.minusSeconds(60))
        val claims = claimer.claimNuVerwerkbaar(maxBatch = 10).filter { it.berichtId == berichtId }
        // Eén voor aanmeld, één voor notificatie (Solo-profiel gebruikt alleen aanmeld → 1 claim).
        assertTrue(claims.size in 1..2)

        claims.forEach { claimer.markeerGeslaagd(it.claimId, nu) }

        val opnieuw = claimer.claimNuVerwerkbaar(maxBatch = 10).filter { it.berichtId == berichtId }
        assertTrue(opnieuw.isEmpty(), "GEPUBLICEERD-rijen mogen niet opnieuw geclaimd worden")
    }

    @Test
    @Transactional
    fun `dubbele planDeliveries voor zelfde berichtId schendt UNIQUE-constraint`() {
        // Borgt dat de UQ_DELIVERY_BERICHT_DOEL constraint daadwerkelijk firet —
        // belangrijkste idempotency-anker tegen dubbele outbox-rijen bij retries
        // van de aanlever-transactie.
        val nu = Instant.now()
        val berichtId = maakBericht(nu)

        val ex = org.junit.jupiter.api.assertThrows<RuntimeException> {
            outbox.planDeliveries(berichtId, nu)
        }
        // PersistenceException of geneste ConstraintViolationException — beide OK.
        val volledig = generateSequence<Throwable>(ex) { it.cause }.joinToString(" → ") {
            "${it::class.simpleName}: ${it.message?.take(100)}"
        }
        assertTrue(
            volledig.contains("constraint", ignoreCase = true) ||
                volledig.contains("unique", ignoreCase = true) ||
                volledig.contains("23505"),
            "verwachte UNIQUE-violation in causal chain, kreeg: $volledig",
        )
    }

    class SoloDownstreamProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "magazijn.publicatie.downstreams.aanmeld.url" to "http://localhost:1/events",
            "quarkus.scheduler.enabled" to "false",
        )
    }
}
