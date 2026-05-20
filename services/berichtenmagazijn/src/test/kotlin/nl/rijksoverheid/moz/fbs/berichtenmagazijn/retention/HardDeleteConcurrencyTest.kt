package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Verifieert dat twee parallelle [HardDeleteService.run]-aanroepen elk bericht
 * hooguit eens verwijderen dankzij `FOR UPDATE SKIP LOCKED` in de claim-query.
 *
 * De test simuleert twee pods die simultaan vuren: elk claimt een disjuncte
 * subset en de som van verwijderde berichten moet exact gelijk zijn aan het
 * totaal aangemaakt — geen overlap, geen verlies.
 */
@QuarkusTest
class HardDeleteConcurrencyTest {

    @Inject
    lateinit var service: HardDeleteService

    @Inject
    lateinit var berichtRepository: BerichtRepository

    @Inject
    lateinit var bijlageRepository: BijlageRepository

    @Inject
    lateinit var statusRepository: BerichtStatusRepository

    @Inject
    lateinit var entityManager: EntityManager

    @BeforeEach
    fun installLdvMock() {
        val ldvMock = mockk<HardDeleteLdvLogger>(relaxed = true)
        QuarkusMock.installMockForType(ldvMock, HardDeleteLdvLogger::class.java)
    }

    @BeforeEach
    @Transactional
    fun cleanDatabase() {
        statusRepository.deleteAll()
        bijlageRepository.deleteAll()
        berichtRepository.deleteAll()
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Slaat een kandidaat-bericht op en stelt via native SQL zowel [tijdstip_ontvangst]
     * als [verwijderd_op] in op 3000 dagen geleden — ruim boven beide retentiedrempels.
     */
    @Transactional
    open fun saveOudKandidaat(): UUID {
        val berichtId = UUID.randomUUID()
        berichtRepository.save(
            Bericht(
                berichtId = berichtId,
                afzender = Oin("00000001003214345000"),
                ontvanger = Bsn("999993653"),
                onderwerp = "Concurrencytest $berichtId",
                inhoud = "Inhoud voor concurrency hard-delete test",
                tijdstipOntvangst = Instant.now().truncatedTo(ChronoUnit.MILLIS),
            ),
        )

        val drempel = Instant.now().minus(3000, ChronoUnit.DAYS)
        entityManager.createNativeQuery(
            "UPDATE berichten SET tijdstip_ontvangst = :t, verwijderd_op = :t WHERE bericht_id = :id",
        )
            .setParameter("t", drempel)
            .setParameter("id", berichtId)
            .executeUpdate()

        entityManager.flush()
        entityManager.clear()

        return berichtId
    }

    // -------------------------------------------------------------------------
    // Test
    // -------------------------------------------------------------------------

    @Test
    fun `twee parallele runs verwijderen elk bericht hooguit eens`() {
        val aantalKandidaten = 10
        repeat(aantalKandidaten) { saveOudKandidaat() }

        val pool = Executors.newFixedThreadPool(2)
        val f1: Future<HardDeleteService.RunResultaat> = pool.submit<HardDeleteService.RunResultaat> { service.run() }
        val f2: Future<HardDeleteService.RunResultaat> = pool.submit<HardDeleteService.RunResultaat> { service.run() }

        val r1 = f1.get(60, TimeUnit.SECONDS)
        val r2 = f2.get(60, TimeUnit.SECONDS)

        pool.shutdown()

        assertEquals(
            aantalKandidaten,
            r1.totaalVerwijderd + r2.totaalVerwijderd,
            "beide threads samen moeten precies $aantalKandidaten berichten verwijderen (geen overlap, geen verlies)",
        )
        assertEquals(0, r1.fouten + r2.fouten, "verwacht geen fouten in beide runs")
    }
}
