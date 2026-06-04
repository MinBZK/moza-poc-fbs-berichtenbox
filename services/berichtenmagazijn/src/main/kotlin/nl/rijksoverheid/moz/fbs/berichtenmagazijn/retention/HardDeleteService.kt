package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset

/**
 * Orchestreert de retentie-run: claim batches via [HardDeleteTransactionalOps.claim],
 * verwijder per bericht via [HardDeleteTransactionalOps.deleteOne] en log één
 * LDV-record per bericht via [HardDeleteLdvLogger.logHardDelete].
 *
 * De loop stopt zodra:
 *  - een batch leeg is, of
 *  - een batch niet vol is (impliciet: minder kandidaten dan `batchGrootte` over), of
 *  - het totaal-geteld `MAX_PER_RUN` bereikt (veiligheidsplafond tegen ongelimiteerde runs).
 *
 * Multi-pod-veiligheid: claim gebruikt `FOR UPDATE SKIP LOCKED`. Pods kunnen dus
 * simultaan vuren maar claimen disjuncte rij-sets. Een lege claim → andere pod
 * is sneller / niets te doen.
 */
@ApplicationScoped
class HardDeleteService(
    private val ops: HardDeleteTransactionalOps,
    private val ldv: HardDeleteLdvLogger,
    private val config: RetentionConfig,
) {

    private val log = Logger.getLogger(HardDeleteService::class.java)

    data class RunResultaat(
        val totaalVerwijderd: Int,
        val fouten: Int,
        val ldvFouten: Int,
        val durationMs: Long,
    )

    private enum class KandidaatUitkomst { VERWIJDERD, VERWIJDERD_LDV_FOUT, OVERGESLAGEN, FOUT }

    fun run(): RunResultaat {
        val start = Instant.now()
        val receiptDeadline = subtract(start, config.minimaleLeeftijd())
        val softDeleteDeadline = subtract(start, config.minimaleSoftDeleteLeeftijd())
        val batchSize = config.batchGrootte()

        var totaal = 0
        var fouten = 0
        var ldvFouten = 0

        loop@ while (totaal < MAX_PER_RUN) {
            val candidates = ops.claim(
                receiptDeadline = receiptDeadline,
                softDeleteDeadline = softDeleteDeadline,
                batchSize = remainingBatchSize(totaal, batchSize),
            )

            if (candidates.isEmpty()) break

            for (candidate in candidates) {
                when (verwijderEnLog(candidate)) {
                    KandidaatUitkomst.VERWIJDERD -> totaal++
                    KandidaatUitkomst.VERWIJDERD_LDV_FOUT -> {
                        totaal++
                        ldvFouten++
                    }
                    KandidaatUitkomst.OVERGESLAGEN -> {}
                    KandidaatUitkomst.FOUT -> fouten++
                }

                if (totaal >= MAX_PER_RUN) {
                    log.infof("hard-delete reached MAX_PER_RUN (%d) — restant in volgende cron-tick", MAX_PER_RUN)
                    break@loop
                }
            }

            if (candidates.size < batchSize) break
        }

        val durationMs = Duration.between(start, Instant.now()).toMillis()
        log.infof(
            "hard-delete run finished totaalVerwijderd=%d fouten=%d ldvFouten=%d durationMs=%d",
            totaal,
            fouten,
            ldvFouten,
            durationMs,
        )
        return RunResultaat(totaal, fouten, ldvFouten, durationMs)
    }

    /**
     * Verwijdert één kandidaat en logt het LDV-record. Fouten per kandidaat blijven
     * geïsoleerd: een mislukte delete of LDV-write stopt de run niet, maar telt mee
     * in de uitkomst zodat [run] ze in [RunResultaat] rapporteert.
     */
    private fun verwijderEnLog(candidate: HardDeleteKandidaat): KandidaatUitkomst {
        val deletedRows = try {
            ops.deleteOne(candidate)
        } catch (ex: Exception) {
            log.errorf(
                ex,
                "hard-delete failed berichtId=%s ontvangerType=%s",
                candidate.berichtId,
                candidate.ontvangerType,
            )

            return KandidaatUitkomst.FOUT
        }

        if (deletedRows == 0) {
            log.warnf(
                "hard-delete affected 0 rows berichtId=%s — overgeslagen door andere pod?",
                candidate.berichtId,
            )

            return KandidaatUitkomst.OVERGESLAGEN
        }

        return try {
            ldv.logHardDelete(candidate)
            KandidaatUitkomst.VERWIJDERD
        } catch (ex: Exception) {
            log.errorf(
                ex,
                "LDV-write failed na hard-delete berichtId=%s ontvangerType=%s",
                candidate.berichtId,
                candidate.ontvangerType,
            )
            KandidaatUitkomst.VERWIJDERD_LDV_FOUT
        }
    }

    private fun remainingBatchSize(totaal: Int, batchSize: Int): Int =
        minOf(batchSize, MAX_PER_RUN - totaal)

    /**
     * `Instant.minus(Period)` werkt niet — `Period` heeft date-units (jaren/maanden)
     * die `Instant` niet kent. We rekenen daarom via `OffsetDateTime` op UTC, wat
     * leap-jaar-correct is voor `P7Y` etc.
     */
    private fun subtract(instant: Instant, period: Period): Instant =
        instant.atOffset(ZoneOffset.UTC).minus(period).toInstant()

    companion object {
        /** Veiligheidsplafond: nooit meer dan dit aantal verwijderingen per cron-run. */
        const val MAX_PER_RUN = 100_000
    }
}
