package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Panache-repository voor bijlagen. Externe code werkt uitsluitend met [Bijlage]
 * en [BijlageMetadata] — [BijlageEntity] is een `internal` implementatiedetail.
 */
@ApplicationScoped
class BijlageRepository : PanacheRepositoryBase<BijlageEntity, Long> {

    /**
     * Persisteert een nieuwe bijlage. Wordt aangeroepen vanuit de Aanlever-flow
     * voor elke bijlage in het aangeleverde bericht. De FK naar `berichten` zorgt
     * dat een bijlage zonder bestaand bericht een DB-constraint-violation geeft.
     */
    fun save(bijlage: Bijlage) {
        persist(
            BijlageEntity().apply {
                bijlageId = bijlage.bijlageId
                berichtId = bijlage.berichtId
                naam = bijlage.naam
                mimeType = bijlage.mimeType
                content = bijlage.content
            },
        )
    }

    /**
     * Haalt één bijlage op (incl. bytes) aan de hand van bericht- én bijlage-ID.
     * Beide IDs moeten matchen, anders 404 — dit voorkomt dat een gokje op een
     * `bijlageId` resultaat geeft bij een willekeurig ander bericht.
     */
    fun findByBerichtIdEnBijlageId(berichtId: UUID, bijlageId: UUID): Bijlage? =
        find("berichtId = ?1 and bijlageId = ?2", berichtId, bijlageId)
            .firstResult()
            ?.toBijlage()

    /**
     * Haalt alleen de metadata (zonder bytes) op van alle bijlagen bij een bericht.
     * Gebruikt door `GET /berichten/{id}` om bijlage-metadata in de response te
     * bouwen. Bij grote bijlagen of veel bijlagen kan een projection-query
     * (`SELECT bijlage_id, naam, mime_type`) efficiënter zijn; voor de PoC is
     * Hibernate's lazy-load van `@Lob`-kolommen voldoende.
     */
    fun metadataVoorBericht(berichtId: UUID): List<BijlageMetadata> =
        find("berichtId", berichtId)
            .list()
            .map { BijlageMetadata(bijlageId = it.bijlageId, naam = it.naam, mimeType = it.mimeType) }
}

private fun BijlageEntity.toBijlage(): Bijlage = Bijlage(
    bijlageId = bijlageId,
    berichtId = berichtId,
    naam = naam,
    mimeType = mimeType,
    content = content,
)
