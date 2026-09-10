package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.util.UUID

/**
 * pgjdbc geeft de databasesessie de standaardtijdzone van de JVM mee, en die volgt `TZ` in de
 * container. `berichten.verwijderd_op` en `bericht_status.gewijzigd_op` zijn kolommen zónder
 * tijdzone; deze test borgt dat een tijdstip daar onder elke sessietijdzone ongeschonden in en uit
 * komt, zodat de tijdzone van de container alleen de logs raakt.
 *
 * De test zet de sessietijdzone zelf, zodat hij niet afhangt van de tijdzone van de machine waarop
 * hij draait — CI draait in UTC en zou een verschuiving anders nooit zien.
 */
@QuarkusTest
class SessietijdzoneIntegrationTest {

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

    @ParameterizedTest(name = "{0} op {1}")
    @CsvSource(textBlock = TIJDZONES_EN_TIJDSTIPPEN)
    @Transactional
    fun `verwijderdOp komt ongeschonden terug na soft-delete`(tijdzone: String, tijdstip: Instant) {
        val berichtId = slaBerichtOp()
        zetSessietijdzone(tijdzone)

        berichtRepository.softDelete(berichtId, ONTVANGER, tijdstip)
        entityManager.flush()
        entityManager.clear()

        assertEquals(tijdstip, berichtRepository.findIncludingDeleted(berichtId)!!.verwijderdOp)
    }

    @ParameterizedTest(name = "{0} op {1}")
    @CsvSource(textBlock = TIJDZONES_EN_TIJDSTIPPEN)
    @Transactional
    fun `hard-delete-claim leest verwijderdOp ongeschonden terug`(tijdzone: String, tijdstip: Instant) {
        val berichtId = slaBerichtOp()
        zetSessietijdzone(tijdzone)
        berichtRepository.softDelete(berichtId, ONTVANGER, tijdstip)
        entityManager.flush()

        val kandidaten = berichtRepository.claimVoorHardDelete(
            receiptDeadline = RUIME_DEADLINE,
            softDeleteDeadline = RUIME_DEADLINE,
            batchSize = 10,
        )

        assertEquals(listOf(tijdstip), kandidaten.map { it.verwijderdOp })
    }

    @ParameterizedTest(name = "{0} op {1}")
    @CsvSource(textBlock = TIJDZONES_EN_TIJDSTIPPEN)
    @Transactional
    fun `gewijzigdOp komt ongeschonden terug na upsert en bij batch-lezen`(tijdzone: String, tijdstip: Instant) {
        val berichtId = slaBerichtOp()
        zetSessietijdzone(tijdzone)

        val bijgewerkt = statusRepository.upsert(berichtId, BerichtStatusPatch(gelezen = true, map = null), tijdstip)
        entityManager.clear()

        assertEquals(tijdstip, bijgewerkt.gewijzigdOp, "teruggelezen binnen de upsert")
        assertEquals(tijdstip, statusRepository.findByBerichtIds(listOf(berichtId))[berichtId]!!.gewijzigdOp)
    }

    private fun slaBerichtOp(): UUID {
        val berichtId = UUID.randomUUID()

        berichtRepository.save(
            Bericht(
                berichtId = berichtId,
                afzender = Oin("00000001003214345000"),
                ontvanger = ONTVANGER,
                onderwerp = "Sessietijdzone",
                inhoud = "Inhoud",
                tijdstipOntvangst = ONTVANGST,
                publicatietijdstip = ONTVANGST,
            ),
        )
        entityManager.flush()

        return berichtId
    }

    /** Alleen voor de lopende transactie (`is_local = true`), dus geen lek naar de pool. */
    private fun zetSessietijdzone(tijdzone: String) {
        entityManager.createNativeQuery("SELECT set_config('TimeZone', :tijdzone, true)")
            .setParameter("tijdzone", tijdzone)
            .singleResult
    }

    private companion object {
        val ONTVANGER = Bsn("999993653")
        val ONTVANGST: Instant = Instant.parse("2026-01-01T00:00:00Z")

        /** Ruim na elk testtijdstip, zodat beide retentiedrempels het bericht zeker meenemen. */
        val RUIME_DEADLINE: Instant = Instant.parse("2100-01-01T00:00:00Z")

        /**
         * UTC is de controle. Amsterdam in zomer- en wintertijd, plus het uur dat bij de
         * wintertijdovergang twee keer voorkomt: 00:30Z en 01:30Z zijn allebei 02:30 wandklok, dus
         * een omrekening naar de sessietijdzone zou ze niet uit elkaar houden. New York wijkt de
         * andere kant op af.
         */
        const val TIJDZONES_EN_TIJDSTIPPEN = """
            UTC,              2026-07-01T10:15:30.123Z
            Europe/Amsterdam, 2026-07-01T10:15:30.123Z
            Europe/Amsterdam, 2026-01-15T10:15:30.123Z
            Europe/Amsterdam, 2026-10-25T00:30:00Z
            Europe/Amsterdam, 2026-10-25T01:30:00Z
            America/New_York, 2026-07-01T10:15:30.123Z"""
    }
}
