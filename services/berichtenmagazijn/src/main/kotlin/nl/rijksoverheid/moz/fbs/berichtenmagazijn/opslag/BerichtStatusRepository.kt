package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.exception.requireValid
import java.time.Instant
import java.util.UUID

/**
 * Panache-repository voor de leesstatus van een bericht. Externe code werkt
 * uitsluitend met [BerichtStatus] — [BerichtStatusEntity] is een `internal`
 * implementatiedetail.
 *
 * Status-rijen worden lazy aangemaakt: de eerste PATCH op een bericht maakt
 * de rij; daarvoor levert [findByBerichtId] simpelweg `null` op (het bericht
 * is nog niet aangeraakt door de ontvanger). De FK naar `berichten` loopt
 * via de surrogate PK; [BerichtRepository.findEntityByBerichtId] vertaalt de
 * business-key.
 */
@ApplicationScoped
class BerichtStatusRepository(
    private val berichtRepository: BerichtRepository,
) : PanacheRepositoryBase<BerichtStatusEntity, Long> {

    /**
     * Haalt de status van een bericht op via de business-key, of `null` als er
     * nog geen status is gezet.
     */
    fun findByBerichtId(berichtId: UUID): BerichtStatus? =
        find("bericht.berichtId", berichtId).firstResult()?.toDomain()

    /**
     * Batch-variant voor lijst-endpoints: laadt statussen voor een verzameling
     * berichten in één query (anders N+1). De parent wordt mee-fetched zodat
     * de map-sleutel — `bericht.berichtId` — geen extra lazy-load triggert.
     */
    fun findByBerichtIds(berichtIds: Collection<UUID>): Map<UUID, BerichtStatus> {
        if (berichtIds.isEmpty()) return emptyMap()
        return getEntityManager()
            .createQuery(
                """
                SELECT bs FROM BerichtStatusEntity bs
                JOIN FETCH bs.bericht be
                WHERE be.berichtId IN :ids
                """.trimIndent(),
                BerichtStatusEntity::class.java,
            )
            .setParameter("ids", berichtIds)
            .resultList
            .associate { it.bericht.berichtId to it.toDomain() }
    }

    /**
     * Maakt een status-rij aan of werkt een bestaande bij. PoC-semantiek (zie
     * docstring van [BerichtStatusPatch]): alleen niet-`null` velden in [patch]
     * vervangen de huidige waarde. Volledig RFC 7396-conforme "wis met `null`"
     * vraagt om een tri-state model (bv. `JsonNullable<T>`); dat valt buiten
     * scope voor de PoC.
     */
    fun upsert(
        berichtId: UUID,
        patch: BerichtStatusPatch,
        tijdstip: Instant,
    ): BerichtStatus {
        val entity = find("bericht.berichtId", berichtId).firstResult()
            ?: BerichtStatusEntity().apply {
                bericht = berichtRepository.findEntityByBerichtId(berichtId)
                    ?: throw IllegalArgumentException(
                        "Bericht niet gevonden voor berichtId=$berichtId",
                    )
            }
        if (patch.gelezen != null) entity.gelezen = patch.gelezen
        if (patch.map != null) entity.map = patch.map
        entity.gewijzigdOp = tijdstip
        persist(entity)
        return entity.toDomain()
    }
}

private fun BerichtStatusEntity.toDomain(): BerichtStatus = BerichtStatus(
    gelezen = gelezen,
    map = map,
    gewijzigdOp = gewijzigdOp,
)

/**
 * In-memory representatie van een PATCH-body. PoC-semantiek: `null` =
 * "veld niet aanwezig in de body, niet wijzigen". Het verschil tussen
 * "afwezig" en "expliciet `null`" wordt niet bewaard — Jackson kan dat zonder
 * extra type-machinerie niet onderscheiden. Daardoor kan een map die eenmaal
 * gezet is, in de PoC niet via deze endpoint gewist worden.
 *
 * Een patch waar alle velden `null` zijn is een no-op die anders stil de
 * `gewijzigdOp`-timestamp zou bumpen zonder semantische wijziging; dat
 * ondergraaft de audit-betekenis. Een lege patch is dus een client-fout (400).
 */
data class BerichtStatusPatch(
    val gelezen: Boolean?,
    val map: String?,
) {
    init {
        requireValid(gelezen != null || map != null) {
            "Patch moet minstens één van 'gelezen' of 'map' bevatten"
        }
    }
}
