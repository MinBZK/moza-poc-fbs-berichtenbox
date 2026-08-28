package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Bijlagen bij een bericht, inclusief de bytes.
 *
 * Net als bij de status zit de discriminator indirect: een bijlage hangt aan één bericht, en de
 * aanroeper heeft dat bericht al binnen zijn magazijn opgezocht.
 */
@ApplicationScoped
class BijlageRepository : PanacheRepositoryBase<BijlageEntity, Long> {

    /**
     * De metadata van een hele pagina berichten in één query. Bewust zonder `inhoud` in het
     * projectieresultaat: de lijst toont alleen namen, en de bytes van twintig berichten meeslepen
     * zou een lijst-response opblazen met data die niemand opvraagt.
     */
    fun metadataVoorBerichten(berichtDbIds: List<Long>): Map<Long, List<BijlageMetadata>> {
        if (berichtDbIds.isEmpty()) return emptyMap()

        @Suppress("UNCHECKED_CAST")
        val rijen = getEntityManager()
            .createQuery(
                "SELECT b.bericht.id, b.bijlageId, b.naam, b.mimeType FROM BijlageEntity b " +
                    "WHERE b.bericht.id IN :ids ORDER BY b.id",
            )
            .setParameter("ids", berichtDbIds)
            .resultList as List<Array<Any>>

        return rijen
            .groupBy { it[0] as Long }
            .mapValues { (_, groep) ->
                groep.map { rij ->
                    BijlageMetadata(
                        bijlageId = rij[1] as UUID,
                        naam = rij[2] as String,
                        mimeType = rij[3] as String,
                    )
                }
            }
    }

    fun zoek(berichtDbId: Long, bijlageId: UUID): Bijlage? =
        find("bericht.id = ?1 and bijlageId = ?2", berichtDbId, bijlageId)
            .firstResult()
            ?.let { rij ->
                Bijlage(
                    bijlageId = rij.bijlageId,
                    naam = rij.naam,
                    mimeType = rij.mimeType,
                    inhoud = rij.inhoud,
                )
            }

    internal fun bewaar(bericht: BerichtEntity, bijlagen: List<Bijlage>) {
        bijlagen.forEach { bijlage ->
            persist(
                BijlageEntity().apply {
                    this.bericht = bericht
                    bijlageId = bijlage.bijlageId
                    naam = bijlage.naam
                    mimeType = bijlage.mimeType
                    inhoud = bijlage.inhoud
                },
            )
        }
    }
}
