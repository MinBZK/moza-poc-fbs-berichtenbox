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
 * bedrijfs-identifier ([Bericht.berichtId]). De naamgeving [vind] benadrukt dat er
 * op de business-key wordt gezocht, niet op het technische `id`.
 */
@ApplicationScoped
class BerichtRepository : PanacheRepositoryBase<BerichtEntity, Long> {

    /** Persisteert een gevalideerd domeinobject. De entity-mapping gebeurt hier. */
    fun opslaan(bericht: Bericht) {
        persist(BerichtEntity.fromDomain(bericht))
    }

    /** Haalt een bericht op als domeinobject. Retourneert `null` als het niet bestaat. */
    fun vind(berichtId: UUID): Bericht? =
        find("berichtId", berichtId).firstResult()?.toDomain()
}
