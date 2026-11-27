package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
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
@TestProfile(SoloDownstreamProfile::class)
class PostgresPublicatieClaimerIntegrationTest {

    @Inject
    lateinit var claimer: PublicatieClaimer

    @Inject
    lateinit var berichten: BerichtRepository

    @Inject
    lateinit var deliveries: PublicatieDeliveryRepository

    @Inject
    lateinit var outbox: PublicatieOutbox

    @Inject
    lateinit var achterstand: PublicatieAchterstand

    @Inject
    lateinit var registry: MeterRegistry

    @Inject
    lateinit var config: PublicatieConfig

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

    // Echte Postgres: de JOIN, de filters, de klok van de database en de typen die pgjdbc teruggeeft.
    @Test
    @Transactional
    fun `de telling van de achterstand telt alleen wat gepubliceerd had mogen zijn, per status en doel`() {
        val nu = Instant.now()
        val ids = List(4) { maakBericht(nu.minusSeconds(60)) }
        maakBericht(nu.plusSeconds(86_400))
        val claims = claimer.claimNuVerwerkbaar(maxBatch = 10).associateBy { it.berichtId }

        claimer.markeerGeslaagd(claims.getValue(ids[0]).claimId, nu)
        claimer.markeerMislukt(claims.getValue(ids[1]).claimId, "test", volgendePoging = null)

        val rijen = achterstand.telRijen().associateBy { it.status to it.doel }
        val doel = claims.getValue(ids[2]).doel.key

        assertEquals(setOf(DeliveryStatus.TE_PUBLICEREN to doel, DeliveryStatus.MISLUKT to doel), rijen.keys)
        assertEquals(2L, rijen.getValue(DeliveryStatus.TE_PUBLICEREN to doel).aantal, "het bericht van morgen telt niet")
        assertEquals(1L, rijen.getValue(DeliveryStatus.MISLUKT to doel).aantal)
        assertTrue(rijen.values.all { it.oudsteSeconden in 0..60 }, rijen.toString())
    }

    @Test
    @Transactional
    fun `een lege outbox levert geen rijen`() {
        assertTrue(achterstand.telRijen().isEmpty())
    }

    // Zonder testtransactie: zo ziet de telling alleen gecommitte rijen, in haar eigen transactie, zoals
    // in productie. Een rij met een vervallen bestemming komt onder _onbekend.
    @Test
    fun `de geplande telling zet de meetwaarden uit gecommitte rijen`() {
        val doel = config.downstreams().keys.single()
        val verleden = Instant.now().minusSeconds(600)

        QuarkusTransaction.requiringNew().run {
            repeat(2) { maakBericht(verleden) }
            val vervallen = maakBericht(verleden)

            deliveries.getEntityManager()
                .createNativeQuery(
                    "UPDATE publicatie_deliveries SET doel = 'vervallen' " +
                        "WHERE bericht_db_id = (SELECT id FROM berichten WHERE bericht_id = :id)",
                )
                .setParameter("id", vervallen)
                .executeUpdate()
        }

        try {
            achterstand.tel()

            val waarde = { naam: String, d: String ->
                registry.get(naam).tags("status", "te_publiceren", "doel", d).gauge().value()
            }

            assertEquals(2.0, waarde("fbs.publicatie.achterstand", doel))
            assertEquals(1.0, waarde("fbs.publicatie.achterstand", DownstreamClient.ONBEKEND_DOEL))
            assertTrue(waarde("fbs.publicatie.achterstand.oudste", doel) >= 0.0)
            assertTrue(registry.get("fbs.publicatie.achterstand.gemeten.op").gauge().value() > 0.0)
        } finally {
            QuarkusTransaction.requiringNew().run {
                deliveries.deleteAll()
                berichten.deleteAll()
            }
        }
    }
}
