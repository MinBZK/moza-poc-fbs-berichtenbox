package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.postgres

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.DeliveryStatus
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieClaim
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieClaimer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieDeliveryEntity
import org.hibernate.LockMode
import org.hibernate.query.Query
import java.time.Clock
import java.time.Instant

/**
 * Postgres-implementatie van [PublicatieClaimer] op basis van
 * `SELECT ... FOR UPDATE SKIP LOCKED`. Andere magazijn-instanties zien de
 * geclaimde rij niet meer in hun query-resultaat en gaan met de volgende rij
 * door — geen duplicate sends bij scale-out, geen contention op de lock.
 *
 * Geïsoleerd in de `postgres/`-subpackage zodat domein-code (`PublicatieStream`,
 * `PublicatieOutbox`) DBMS-onafhankelijk blijft. Vervangen door een andere
 * adapter (bv. in-memory voor testen) raakt alleen deze klasse.
 */
@ApplicationScoped
internal class PostgresPublicatieClaimer(
    private val entityManager: EntityManager,
    private val clock: Clock,
) : PublicatieClaimer {

    @Transactional(Transactional.TxType.MANDATORY)
    override fun claimNuVerwerkbaar(maxBatch: Int): List<PublicatieClaim> {
        val query = entityManager.createQuery(
            """
            SELECT d FROM PublicatieDeliveryEntity d
             WHERE d.status = :status
               AND d.volgendePoging <= :nu
             ORDER BY d.volgendePoging ASC
            """.trimIndent(),
            PublicatieDeliveryEntity::class.java,
        )
            .setParameter("status", DeliveryStatus.TE_PUBLICEREN)
            .setParameter("nu", clock.instant())
            .setMaxResults(maxBatch)
            .unwrap(Query::class.java)
            .setHibernateLockMode(LockMode.UPGRADE_SKIPLOCKED)

        @Suppress("UNCHECKED_CAST")
        val rijen = query.resultList as List<PublicatieDeliveryEntity>
        return rijen.map { it.toClaim() }
    }

    /**
     * Bij MANDATORY-contract zit de claim-lock vast binnen dezelfde transactie;
     * de delivery-rij kan dus logisch gezien niet verdwijnen. Verdwijnt hij
     * tóch, dan is het claim-contract gebroken (transactie-grens, externe
     * mutatie). We gooien een `IllegalStateException` zodat de transactie
     * rollbacked, het lock vrijkomt, en de outbox-rij in zijn `TE_PUBLICEREN`-
     * staat blijft staan i.p.v. dat een 'no-op' duplicate sends veroorzaakt.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    override fun markeerGeslaagd(claimId: Long, tijdstip: Instant) {
        val entity = entityManager.find(PublicatieDeliveryEntity::class.java, claimId)
            ?: throw IllegalStateException(
                "markeerGeslaagd: delivery claimId=$claimId niet gevonden ondanks claim-lock; " +
                    "transactie- of MANDATORY-contract gebroken",
            )
        entity.markeerGeslaagd(tijdstip)
    }

    @Transactional(Transactional.TxType.MANDATORY)
    override fun markeerMislukt(claimId: Long, fout: String, volgendePoging: Instant?) {
        val entity = entityManager.find(PublicatieDeliveryEntity::class.java, claimId)
            ?: throw IllegalStateException(
                "markeerMislukt: delivery claimId=$claimId niet gevonden ondanks claim-lock; " +
                    "transactie- of MANDATORY-contract gebroken",
            )
        entity.markeerMislukt(fout, volgendePoging)
    }
}
