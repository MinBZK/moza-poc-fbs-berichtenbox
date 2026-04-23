package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Panache-repository voor berichten. Externe code werkt uitsluitend met [Bericht]
 * domeinobjecten — [BerichtEntity] is een `internal` implementatiedetail.
 *
 * Het generieke type-parameter `Long` verwijst naar de surrogate primary key van
 * [BerichtEntity]; externe callers adresseren berichten echter via de
 * bedrijfs-identifier ([Bericht.berichtId]). De naam [findByBerichtId] onderscheidt
 * dit expliciet van Panache's `findById` (surrogate PK).
 */
@ApplicationScoped
class BerichtRepository : PanacheRepositoryBase<BerichtEntity, Long> {

    /** Persisteert een gevalideerd domeinobject. De entity-mapping gebeurt hier. */
    fun save(bericht: Bericht) {
        persist(BerichtEntity.fromDomain(bericht))
    }

    /** Haalt een bericht op via de business-key. Retourneert `null` als het niet bestaat. */
    fun findByBerichtId(berichtId: UUID): Bericht? =
        find("berichtId", berichtId).firstResult()?.toDomain()
}
