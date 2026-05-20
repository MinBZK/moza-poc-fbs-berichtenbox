package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository

/**
 * Per-bericht-delete in een eigen sub-transactie (`REQUIRES_NEW`) zodat een
 * fout op één bericht de andere niet meesleurt. Aparte bean (geen methode op
 * [HardDeleteService]) omdat `@Transactional` op een Kotlin-methode alleen
 * werkt via een CDI-proxy — een interne `this.x()`-call zou de transactie
 * niet starten.
 *
 * Volgorde: bijlagen → status → bericht. FK's zijn RESTRICT, dus child-rijen
 * moeten vóór de parent weg.
 */
@ApplicationScoped
class HardDeleteTransactionalOps(
    private val bijlageRepository: BijlageRepository,
    private val statusRepository: BerichtStatusRepository,
    private val berichtRepository: BerichtRepository,
) {

    /** Retourneert het aantal verwijderde bericht-rijen (0 of 1). */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun deleteOne(candidate: HardDeleteCandidaat): Int {
        bijlageRepository.deleteByBerichtDbId(candidate.id)
        statusRepository.deleteByBerichtDbId(candidate.id)
        return berichtRepository.hardDeleteByDbId(candidate.id)
    }
}
