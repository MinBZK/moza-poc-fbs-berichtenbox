package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Integratietests voor de `deleteByBerichtDbId`-methoden op [BijlageRepository]
 * en [BerichtStatusRepository]. Valideert dat bulk-delete correct werkt als
 * voorbereiding op de harde verwijdering van de parent-rij (RESTRICT-FK).
 */
@QuarkusTest
class BijlageStatusDeleteIntegrationTest {

    @Inject
    lateinit var berichtRepository: BerichtRepository

    @Inject
    lateinit var bijlageRepository: BijlageRepository

    @Inject
    lateinit var statusRepository: BerichtStatusRepository

    @BeforeEach
    @Transactional
    fun clean() {
        statusRepository.deleteAll()
        bijlageRepository.deleteAll()
        berichtRepository.deleteAll()
    }

    @Test
    @Transactional
    fun `deleteByBerichtDbId verwijdert alle bijlagen van een bericht`() {
        val berichtId = UUID.randomUUID()
        slaBerichtOp(berichtId)
        val berichtDbId = berichtRepository.findDbIdByBerichtId(berichtId)!!

        repeat(3) { i ->
            bijlageRepository.save(
                Bijlage(
                    bijlageId = UUID.randomUUID(),
                    berichtId = berichtId,
                    naam = "bijlage-$i.pdf",
                    mimeType = "application/pdf",
                    content = "inhoud$i".toByteArray(),
                ),
            )
        }
        assertEquals(3L, bijlageRepository.count(), "verwacht 3 bijlagen vóór delete")

        val aantalVerwijderd = bijlageRepository.deleteByBerichtDbId(berichtDbId)

        assertEquals(3, aantalVerwijderd, "deleteByBerichtDbId moet 3 rijen verwijderen")
        assertEquals(0L, bijlageRepository.count(), "na delete mogen geen bijlagen meer over zijn")
    }

    @Test
    @Transactional
    fun `deleteByBerichtDbId op berichtstatus verwijdert de status-rij`() {
        val berichtId = UUID.randomUUID()
        slaBerichtOp(berichtId)
        val berichtDbId = berichtRepository.findDbIdByBerichtId(berichtId)!!

        statusRepository.upsert(berichtId, BerichtStatusPatch(gelezen = true, map = null), Instant.now())

        val aantalVerwijderd = statusRepository.deleteByBerichtDbId(berichtDbId)

        assertEquals(1, aantalVerwijderd, "deleteByBerichtDbId moet 1 status-rij verwijderen")
        assertNull(
            statusRepository.findByBerichtId(berichtId),
            "na delete mag er geen status-rij meer bestaan",
        )
    }

    @Test
    @Transactional
    fun `deleteByBerichtDbId is idempotent — tweede keer 0`() {
        val berichtId = UUID.randomUUID()
        slaBerichtOp(berichtId)
        val berichtDbId = berichtRepository.findDbIdByBerichtId(berichtId)!!

        bijlageRepository.save(
            Bijlage(
                bijlageId = UUID.randomUUID(),
                berichtId = berichtId,
                naam = "enige-bijlage.pdf",
                mimeType = "application/pdf",
                content = "inhoud".toByteArray(),
            ),
        )

        val eersteDelete = bijlageRepository.deleteByBerichtDbId(berichtDbId)
        val tweedeDelete = bijlageRepository.deleteByBerichtDbId(berichtDbId)

        assertEquals(1, eersteDelete, "eerste delete moet 1 rij verwijderen")
        assertEquals(0, tweedeDelete, "tweede delete moet 0 rijen teruggeven (idempotent)")
    }

    private fun slaBerichtOp(berichtId: UUID) {
        berichtRepository.save(
            Bericht(
                berichtId = berichtId,
                afzender = Oin("00000001003214345000"),
                ontvanger = Bsn("999993653"),
                onderwerp = "Test bericht",
                inhoud = "Test inhoud",
                tijdstipOntvangst = Instant.now().truncatedTo(ChronoUnit.MILLIS),
                publicatietijdstip = Instant.now().truncatedTo(ChronoUnit.MILLIS),
            ),
        )
    }
}
