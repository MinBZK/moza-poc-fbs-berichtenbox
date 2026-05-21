package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import java.time.Instant
import java.util.UUID

/**
 * Panache-repository voor [PublicatieDeliveryEntity]. Wordt geïnjecteerd in
 * [PublicatieOutbox] (schrijfpad) en in de Postgres-claimer (leespad).
 *
 * De FK naar `berichten` loopt via de surrogate PK (`bericht_db_id` → `berichten.id`);
 * [BerichtRepository] levert de [nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtEntity]-
 * referentie op basis van de business-key zodat callers met de UUID kunnen blijven werken
 * (zelfde patroon als `BijlageRepository`).
 */
@ApplicationScoped
class PublicatieDeliveryRepository(
    private val berichtRepository: BerichtRepository,
) : PanacheRepositoryBase<PublicatieDeliveryEntity, Long> {

    /**
     * Persisteert een nieuwe delivery-rij. Resolvet de business-key naar de
     * parent-[nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtEntity]
     * zodat de FK op de surrogate PK gezet wordt. Het parent-bericht hoort in
     * dezelfde transactie al gepersisteerd te zijn; een ontbrekend parent duidt
     * op een programmeerfout.
     */
    internal fun planNieuwe(
        berichtId: UUID,
        doel: Publicatiedoel,
        volgendePoging: Instant,
        aangemaaktOp: Instant,
    ) {
        val berichtEntity = berichtRepository.findEntityByBerichtId(berichtId)
            ?: throw IllegalStateException(
                "Parent-bericht ontbreekt voor publicatie-delivery berichtId=$berichtId",
            )
        persist(
            PublicatieDeliveryEntity.nieuwe(
                bericht = berichtEntity,
                doel = doel,
                volgendePoging = volgendePoging,
                aangemaaktOp = aangemaaktOp,
            ),
        )
    }

    internal fun findByBerichtId(berichtId: UUID): List<PublicatieDeliveryEntity> =
        list("bericht.berichtId", berichtId)
}
