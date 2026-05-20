package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusPatch
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * End-to-end integratietests voor [HardDeleteService.run] met een echte Postgres-container
 * (Dev Services / Testcontainers). [HardDeleteLdvLogger] wordt gemockt via
 * [QuarkusMock.installMockForType] zodat geen ClickHouse-verbinding nodig is en calls
 * geverifieerd kunnen worden.
 *
 * De retentiedrempel is P7Y (≈ 2557 dagen). Berichten worden aangemaakt met offsets van
 * -3000 dagen zodat beide drempels zeker gehaald worden.
 */
@QuarkusTest
class HardDeleteJobIntegrationTest {

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

    private lateinit var ldvMock: HardDeleteLdvLogger

    @BeforeEach
    fun setUp() {
        ldvMock = mockk(relaxed = true)
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
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Slaat een bericht op en overschrijft via native SQL de timestamps zodat
     * retentie-drempels gesimuleerd worden zonder de clock te mocken.
     *
     * @param ontvangstOffsetDagen negatief = in het verleden (bv. -3000 = 3000 dagen geleden)
     * @param verwijderdOpOffsetDagen negatief = in het verleden; null = niet soft-deleted
     */
    @Transactional
    fun saveBerichtMetLeeftijd(
        ontvangstOffsetDagen: Long,
        verwijderdOpOffsetDagen: Long?,
    ): UUID {
        val berichtId = UUID.randomUUID()
        berichtRepository.save(
            Bericht(
                berichtId = berichtId,
                afzender = Oin("00000001003214345000"),
                ontvanger = Bsn("999993653"),
                onderwerp = "Retentietest $berichtId",
                inhoud = "Inhoud voor hard-delete integratietest",
                tijdstipOntvangst = Instant.now().truncatedTo(ChronoUnit.MILLIS),
            ),
        )

        entityManager.createNativeQuery(
            "UPDATE berichten SET tijdstip_ontvangst = :t WHERE bericht_id = :id",
        )
            .setParameter("t", Instant.now().plus(ontvangstOffsetDagen, ChronoUnit.DAYS))
            .setParameter("id", berichtId)
            .executeUpdate()

        if (verwijderdOpOffsetDagen != null) {
            entityManager.createNativeQuery(
                "UPDATE berichten SET verwijderd_op = :t WHERE bericht_id = :id",
            )
                .setParameter("t", Instant.now().plus(verwijderdOpOffsetDagen, ChronoUnit.DAYS))
                .setParameter("id", berichtId)
                .executeUpdate()
        }

        entityManager.flush()
        entityManager.clear()

        return berichtId
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `bericht met bijlagen en status, beide drempels gehaald, wordt volledig gewist`() {
        val berichtId = saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -3000,
        )
        saveBijlagenEnStatus(berichtId)

        val resultaat = service.run()

        assertEquals(1, resultaat.totaalVerwijderd, "verwacht 1 verwijderd bericht")
        assertEquals(0, resultaat.fouten, "verwacht geen fouten")
        assertEquals(0, resultaat.ldvFouten, "verwacht geen LDV-fouten")
        assertNull(
            berichtRepository.findIncludingDeleted(berichtId),
            "bericht moet volledig uit DB zijn",
        )
        assertEquals(0L, bijlageRepository.count(), "bijlagen moeten weg zijn")
        assertNull(statusRepository.findByBerichtId(berichtId), "status moet weg zijn")
        verify(exactly = 1) { ldvMock.logHardDelete(match { it.berichtId == berichtId }) }
    }

    @Transactional
    open fun saveBijlagenEnStatus(berichtId: UUID) {
        bijlageRepository.save(
            Bijlage(
                bijlageId = UUID.randomUUID(),
                berichtId = berichtId,
                naam = "bijlage-1.pdf",
                mimeType = "application/pdf",
                content = "inhoud1".toByteArray(),
            ),
        )
        bijlageRepository.save(
            Bijlage(
                bijlageId = UUID.randomUUID(),
                berichtId = berichtId,
                naam = "bijlage-2.pdf",
                mimeType = "application/pdf",
                content = "inhoud2".toByteArray(),
            ),
        )
        statusRepository.upsert(berichtId, BerichtStatusPatch(gelezen = true, map = null), Instant.now())
    }

    @Test
    fun `recent soft-deleted bericht blijft staan`() {
        saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -1, // te recent — drempel niet gehaald
        )

        val resultaat = service.run()

        assertEquals(0, resultaat.totaalVerwijderd, "bericht is te recent soft-deleted — moet blijven")
        assertEquals(1L, berichtRepository.count(), "bericht moet nog in DB zijn")
        verify(exactly = 0) { ldvMock.logHardDelete(any()) }
    }

    @Test
    fun `niet-soft-deleted bericht blijft staan ongeacht leeftijd`() {
        saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = null, // nooit soft-deleted
        )

        val resultaat = service.run()

        assertEquals(0, resultaat.totaalVerwijderd, "niet-soft-deleted bericht mag nooit hard-deleted worden")
        assertEquals(1L, berichtRepository.count(), "bericht moet nog in DB zijn")
        verify(exactly = 0) { ldvMock.logHardDelete(any()) }
    }

    @Test
    fun `FIFO — oudste soft-delete wordt eerst verwijderd`() {
        val ouder = saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -3000, // oudste soft-delete
        )
        val jonger = saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -2900, // jongere soft-delete, maar nog steeds boven drempel
        )

        val aanroepVolgorde = mutableListOf<UUID>()
        every { ldvMock.logHardDelete(any()) } answers { call ->
            aanroepVolgorde.add((call.invocation.args[0] as HardDeleteCandidaat).berichtId)
        }

        val resultaat = service.run()

        assertEquals(2, resultaat.totaalVerwijderd, "beide berichten moeten verwijderd zijn")
        assertEquals(
            listOf(ouder, jonger),
            aanroepVolgorde,
            "oudste soft-delete moet eerst verwerkt worden (ORDER BY verwijderd_op ASC)",
        )
    }

    @Test
    fun `LDV-failure laat DB-delete intact en wordt gerapporteerd`() {
        val berichtId = saveBerichtMetLeeftijd(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -3000,
        )

        every { ldvMock.logHardDelete(any()) } throws RuntimeException("LDV-endpoint niet bereikbaar")

        val resultaat = service.run()

        assertEquals(1, resultaat.totaalVerwijderd, "DB-delete moet geslaagd zijn ondanks LDV-fout")
        assertEquals(1, resultaat.ldvFouten, "verwacht 1 LDV-fout gerapporteerd")
        assertEquals(0, resultaat.fouten, "verwacht geen reguliere fouten")
        assertNull(
            berichtRepository.findIncludingDeleted(berichtId),
            "bericht moet uit DB zijn — LDV-fout mag delete niet terugdraaien",
        )
    }
}
