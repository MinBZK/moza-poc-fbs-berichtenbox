package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Panache-repository voor [PublicatieDeliveryEntity]. Wordt geïnjecteerd in
 * [PublicatieOutbox] (schrijfpad) en in de Postgres-claimer (leespad).
 */
@ApplicationScoped
class PublicatieDeliveryRepository : PanacheRepositoryBase<PublicatieDeliveryEntity, Long> {

    internal fun findByBerichtId(berichtId: UUID): List<PublicatieDeliveryEntity> =
        list("berichtId", berichtId)
}
