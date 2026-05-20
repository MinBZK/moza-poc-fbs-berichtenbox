package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Tuple
import jakarta.ws.rs.NotFoundException
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
     * berichten in één query (anders N+1). Projection-query met alleen de
     * status-kolommen + de business-key van de parent — we hebben de
     * `BerichtEntity`-row hier niet nodig (caller kent de UUIDs al), dus een
     * `JOIN FETCH bs.bericht` zou onnodig de volledige bericht-row (incl. de
     * tot 1 MiB grote `inhoud`-kolom) per status meeladen.
     *
     * Benoemde aliassen ([BERICHT_ID_ALIAS] etc.) voorkomen onbegrijpelijke
     * `ClassCastException` bij JPQL- of entity-drift.
     */
    fun findByBerichtIds(berichtIds: Collection<UUID>): Map<UUID, BerichtStatus> {
        if (berichtIds.isEmpty()) return emptyMap()
        val rijen = getEntityManager()
            .createQuery(
                """
                SELECT bs.bericht.berichtId AS $BERICHT_ID_ALIAS,
                       bs.gelezen          AS $GELEZEN_ALIAS,
                       bs.map              AS $MAP_ALIAS,
                       bs.gewijzigdOp      AS $GEWIJZIGD_OP_ALIAS
                FROM BerichtStatusEntity bs
                WHERE bs.bericht.berichtId IN :ids
                """.trimIndent(),
                Tuple::class.java,
            )
            .setParameter("ids", berichtIds)
            .resultList

        // Statussen zijn 1-op-1 met bericht (unique constraint op bericht_db_id);
        // `associate` zou een duplicaat stilletjes overschrijven en de fout
        // verbergen. groupBy + size-check faalt expliciet bij datacorruptie.
        return rijen.groupBy { it.get(BERICHT_ID_ALIAS, UUID::class.java) }
            .mapValues { (id, groep) ->
                check(groep.size == 1) {
                    "Meerdere statusrijen voor berichtId=$id (uniciteit op bericht_db_id verloren?)"
                }
                val tuple = groep.single()
                BerichtStatus(
                    gelezen = tuple.get(GELEZEN_ALIAS, java.lang.Boolean::class.java).booleanValue(),
                    map = tuple.get(MAP_ALIAS, String::class.java),
                    gewijzigdOp = tuple.get(GEWIJZIGD_OP_ALIAS, Instant::class.java),
                )
            }
    }

    private companion object {
        private const val BERICHT_ID_ALIAS = "berichtId"
        private const val GELEZEN_ALIAS = "gelezen"
        private const val MAP_ALIAS = "map"
        private const val GEWIJZIGD_OP_ALIAS = "gewijzigdOp"
    }

    /**
     * Maakt een status-rij aan of werkt een bestaande bij. Alleen niet-`null`
     * velden in [patch] vervangen de huidige waarde — zie de kdoc van
     * [BerichtStatusPatch] voor de semantiek en de bewuste keuze om een
     * gezette `map` niet via deze endpoint te kunnen wissen.
     *
     * Implementatie: Postgres `INSERT … ON CONFLICT (bericht_db_id) DO UPDATE`
     * met `COALESCE`. Eén atomaire write voorkomt de race waarin twee
     * gelijktijdige PATCHes op hetzelfde bericht beide de `find` missen, beide
     * een nieuwe rij proberen te persisten, en de tweede faalt op het unieke-
     * key. `COALESCE` bewaart bestaande waardes als de patch een veld op `null`
     * laat. Een separate SELECT leest de persisted state terug binnen dezelfde
     * transactie (Hibernate's native `RETURNING` mapt onbetrouwbaar).
     */
    fun upsert(
        berichtId: UUID,
        patch: BerichtStatusPatch,
        tijdstip: Instant,
    ): BerichtStatus {
        val berichtDbId = berichtRepository.findDbIdByBerichtId(berichtId)
            ?: throw NotFoundException("Bericht niet gevonden")

        val em = getEntityManager()
        em.createNativeQuery(
            """
            INSERT INTO bericht_status (bericht_db_id, gelezen, map, gewijzigd_op)
            VALUES (:berichtDbId, COALESCE(:gelezen, false), :map, :tijdstip)
            ON CONFLICT (bericht_db_id) DO UPDATE
            SET gelezen      = COALESCE(:gelezen, bericht_status.gelezen),
                map          = COALESCE(:map, bericht_status.map),
                gewijzigd_op = :tijdstip
            """.trimIndent(),
        )
            .setParameter("berichtDbId", berichtDbId)
            .setParameter("gelezen", patch.gelezen)
            .setParameter("map", patch.map)
            .setParameter("tijdstip", tijdstip)
            .executeUpdate()
        em.flush()

        return em.createQuery(
            "SELECT bs FROM BerichtStatusEntity bs WHERE bs.bericht.id = :id",
            BerichtStatusEntity::class.java,
        )
            .setParameter("id", berichtDbId)
            .singleResult
            .toDomain()
    }
}

private fun BerichtStatusEntity.toDomain(): BerichtStatus = BerichtStatus(
    gelezen = gelezen,
    map = map,
    gewijzigdOp = gewijzigdOp,
)

/**
 * In-memory representatie van een PATCH-body. `null` betekent "veld niet
 * aanwezig in de body, niet wijzigen". Het verschil tussen "afwezig" en
 * "expliciet `null`" wordt bewust niet bewaard: Jackson kan dat zonder
 * tri-state typemachinerie niet onderscheiden, en zolang er geen duidelijke
 * use-case is voor het wissen van een map kiezen we voor de eenvoudige
 * semantiek. Een eenmaal gezette map kan via deze endpoint dus alleen worden
 * overschreven met een nieuwe waarde; wissen vergt een toekomstige
 * sentinel-waarde of aparte endpoint.
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
