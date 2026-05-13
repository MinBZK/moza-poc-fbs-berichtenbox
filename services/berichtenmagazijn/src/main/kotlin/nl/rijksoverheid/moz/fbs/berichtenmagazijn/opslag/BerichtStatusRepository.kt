package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/**
 * Panache-repository voor de leesstatus per (bericht, ontvanger). Externe code
 * werkt uitsluitend met [BerichtStatus] — [BerichtStatusEntity] is een
 * `internal` implementatiedetail.
 *
 * Status-rijen worden lazy aangemaakt: de eerste PATCH op een bericht door een
 * ontvanger maakt de rij; daarvoor levert [findByBerichtIdEnOntvanger] simpelweg
 * `null` op (de ontvanger heeft het bericht nog niet aangeraakt).
 */
@ApplicationScoped
class BerichtStatusRepository : PanacheRepositoryBase<BerichtStatusEntity, BerichtStatusKey> {

    /**
     * Haalt de status van een bericht voor een specifieke ontvanger op, of
     * `null` als de ontvanger nog geen status heeft gezet.
     */
    fun findByBerichtIdEnOntvanger(
        berichtId: UUID,
        ontvanger: Identificatienummer,
    ): BerichtStatus? = findById(BerichtStatusKey(berichtId, ontvanger))?.toDomain()

    /**
     * Maakt een status-rij aan of werkt een bestaande bij. PoC-semantiek (zie
     * docstring van [BerichtStatusPatch]): alleen niet-`null` velden in [patch]
     * vervangen de huidige waarde. Volledig RFC 7396-conforme "wis met `null`"
     * vraagt om een tri-state model (bv. `JsonNullable<T>`); dat valt buiten
     * scope voor de PoC.
     */
    fun upsert(
        berichtId: UUID,
        ontvanger: Identificatienummer,
        patch: BerichtStatusPatch,
        tijdstip: Instant,
    ): BerichtStatus {
        val key = BerichtStatusKey(berichtId, ontvanger)
        val entity = findById(key) ?: BerichtStatusEntity().apply { id = key }
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
 */
data class BerichtStatusPatch(
    val gelezen: Boolean?,
    val map: String?,
)
