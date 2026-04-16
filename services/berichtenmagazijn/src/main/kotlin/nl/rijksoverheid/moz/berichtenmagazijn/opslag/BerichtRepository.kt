package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Panache-repository voor berichten. Externe code werkt uitsluitend met [Bericht]
 * domeinobjecten — [BerichtEntity] is een `internal` implementatiedetail.
 */
@ApplicationScoped
class BerichtRepository : PanacheRepositoryBase<BerichtEntity, UUID> {

    /** Persisteert een gevalideerd domeinobject. De entity-mapping gebeurt hier. */
    fun opslaan(bericht: Bericht) {
        persist(BerichtEntity.fromDomain(bericht))
    }

    /** Haalt een bericht op als domeinobject. Retourneert `null` als het niet bestaat. */
    fun vind(berichtId: UUID): Bericht? = findById(berichtId)?.toDomain()
}
