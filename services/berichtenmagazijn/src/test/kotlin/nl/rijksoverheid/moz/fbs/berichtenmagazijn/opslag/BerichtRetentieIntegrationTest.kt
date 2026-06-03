package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Integratietests voor de retentie-specifieke methoden op [BerichtRepository]:
 * [BerichtRepository.claimVoorHardDelete] en [BerichtRepository.hardDeleteByDbId].
 *
 * [claimVoorHardDelete] gebruikt `FOR UPDATE SKIP LOCKED` — dat vereist een actieve
 * transactie. Elke test is daarom `@Transactional` zodat de lock in scope blijft
 * totdat de test commit.
 */
@QuarkusTest
class BerichtRetentieIntegrationTest {

    @Inject
    lateinit var berichtRepository: BerichtRepository

    @Inject
    lateinit var bijlageRepository: BijlageRepository

    @Inject
    lateinit var statusRepository: BerichtStatusRepository

    @Inject
    lateinit var entityManager: EntityManager

    @BeforeEach
    @Transactional
    fun clean() {
        statusRepository.deleteAll()
        bijlageRepository.deleteAll()
        berichtRepository.deleteAll()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Slaat een bericht op en overschrijft daarna via native SQL de timestamps
     * zodat we retentie-drempels kunnen simuleren zonder de clock te mocken.
     * Retourneert de berichtId als business-key.
     *
     * Gebruik vaste OIN/BSN die de invarianten halen; variëer alleen het
     * onderwerp op basis van een random UUID zodat elke call een uniek bericht
     * aanmaakt (geen unique-key collision op bericht_id).
     */
    @Transactional
    fun saveBerichtMetLeeftijd(ontvangstOffsetDagen: Long, verwijderdOpOffsetDagen: Long?): UUID {
        val berichtId = UUID.randomUUID()

        berichtRepository.save(
            Bericht(
                berichtId = berichtId,
                afzender = Oin("00000001003214345000"),
                ontvanger = Bsn("999993653"),
                onderwerp = "Retentietest $berichtId",
                inhoud = "Inhoud voor retentietest",
                tijdstipOntvangst = Instant.now().truncatedTo(ChronoUnit.MILLIS),
                publicatiedatum = Instant.now().truncatedTo(ChronoUnit.MILLIS),
            ),
        )

        val ontvangstTijdstip = Instant.now().plus(ontvangstOffsetDagen, ChronoUnit.DAYS)
        entityManager.createNativeQuery(
            "UPDATE berichten SET tijdstip_ontvangst = :t WHERE bericht_id = :id",
        )
            .setParameter("t", ontvangstTijdstip)
            .setParameter("id", berichtId)
            .executeUpdate()

        if (verwijderdOpOffsetDagen != null) {
            val verwijderdTijdstip = Instant.now().plus(verwijderdOpOffsetDagen, ChronoUnit.DAYS)
            entityManager.createNativeQuery(
                "UPDATE berichten SET verwijderd_op = :t WHERE bericht_id = :id",
            )
                .setParameter("t", verwijderdTijdstip)
                .setParameter("id", berichtId)
                .executeUpdate()
        }

        entityManager.flush()
        entityManager.clear()

        return berichtId
    }

    // -------------------------------------------------------------------------
    // Tests: claimVoorHardDelete
    // -------------------------------------------------------------------------

    @Test
    @Transactional
    fun `claimVoorHardDelete vindt alleen rijen waarvan beide drempels gehaald zijn`() {
        // Beide drempels gehaald — kandidaat
        val kandidaat = saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -3000,
        )
        // Soft-delete te recent — NIET in result
        saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -1,
        )
        // Ontvangst te recent (theoretisch onmogelijk maar ankert beide drempels) — NIET in result
        saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -1,
            verwijderdOpOffsetDagen = -3000,
        )
        // Nooit soft-deleted — NIET in result
        saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = null,
        )

        val deadline = Instant.now().minus(7 * 365, ChronoUnit.DAYS)
        val candidates = berichtRepository.claimVoorHardDelete(
            receiptDeadline = deadline,
            softDeleteDeadline = deadline,
            batchSize = 100,
        )

        assertEquals(1, candidates.size, "verwacht exact 1 kandidaat")
        assertEquals(kandidaat, candidates.single().berichtId)
    }

    @Test
    @Transactional
    fun `claimVoorHardDelete sorteert oudste soft-delete eerst`() {
        val ouder = saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -3000,
        )
        val jonger = saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -2900,
        )

        val deadline = Instant.now().minus(7 * 365, ChronoUnit.DAYS)
        val candidates = berichtRepository.claimVoorHardDelete(
            receiptDeadline = deadline,
            softDeleteDeadline = deadline,
            batchSize = 100,
        )

        assertEquals(2, candidates.size, "verwacht 2 kandidaten")
        assertEquals(
            listOf(ouder, jonger),
            candidates.map { it.berichtId },
            "oudste soft-delete moet eerst komen (ORDER BY verwijderd_op ASC)",
        )
    }

    @Test
    @Transactional
    fun `claimVoorHardDelete respecteert batchSize`() {
        repeat(3) {
            saveBerichtMetLeeftijd(
                ontvangstOffsetDagen = -3000,
                verwijderdOpOffsetDagen = -3000,
            )
        }

        val deadline = Instant.now().minus(7 * 365, ChronoUnit.DAYS)
        val candidates = berichtRepository.claimVoorHardDelete(
            receiptDeadline = deadline,
            softDeleteDeadline = deadline,
            batchSize = 2,
        )

        assertEquals(2, candidates.size, "batchSize=2 mag maximaal 2 candidates teruggeven")
    }

    // -------------------------------------------------------------------------
    // Tests: hardDeleteByDbId
    // -------------------------------------------------------------------------

    @Test
    @Transactional
    fun `hardDeleteByDbId verwijdert de bericht-rij en retourneert 1`() {
        val berichtId = saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -3000,
        )
        val deadline = Instant.now().minus(7 * 365, ChronoUnit.DAYS)
        val candidates = berichtRepository.claimVoorHardDelete(
            receiptDeadline = deadline,
            softDeleteDeadline = deadline,
            batchSize = 1,
        )
        assertEquals(1, candidates.size, "setup: verwacht 1 kandidaat")
        val dbId = candidates.single().id

        val verwijderd = berichtRepository.hardDeleteByDbId(dbId)

        assertEquals(1, verwijderd, "hardDeleteByDbId moet 1 rij verwijderen")
        assertNull(
            berichtRepository.findIncludingDeleted(berichtId),
            "na hard-delete mag findIncludingDeleted niets teruggeven",
        )
    }
}
