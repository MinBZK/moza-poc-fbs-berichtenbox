package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention.HardDeleteCandidaat
import java.time.Instant
import java.util.UUID

/**
 * Panache-repository voor berichten. Externe code werkt uitsluitend met [Bericht]
 * domeinobjecten — [BerichtEntity] is een `internal` implementatiedetail.
 *
 * Het generieke type-parameter `Long` verwijst naar de surrogate primary key van
 * [BerichtEntity]; externe callers adresseren berichten echter via de
 * bedrijfs-identifier ([Bericht.berichtId]). De naam [findByBerichtId] onderscheidt
 * dit expliciet van Panache's `findById` (surrogate PK).
 *
 * Ophaal-queries filteren standaard verwijderde rijen weg (`verwijderdOp IS NULL`)
 * zodat soft-delete-semantiek consistent is.
 */
@ApplicationScoped
class BerichtRepository : PanacheRepositoryBase<BerichtEntity, Long> {

    /** Persisteert een gevalideerd domeinobject. De entity-mapping gebeurt hier. */
    fun save(bericht: Bericht) {
        persist(BerichtEntity.fromDomain(bericht))
    }

    /**
     * Haalt een actief bericht op via de business-key. Verwijderde berichten
     * (soft-delete) worden uitgesloten.
     */
    fun findByBerichtId(berichtId: UUID): Bericht? =
        find("berichtId = ?1 and verwijderdOp is null", berichtId)
            .firstResult()
            ?.toDomain()

    /**
     * Helper voor sibling-repositories die een FK op `berichten.id` zetten:
     * vertaalt de business-key naar de bijbehorende [BerichtEntity]. Filtert
     * NIET op soft-delete — child-rijen mogen na soft-delete nog steeds bestaan.
     */
    internal fun findEntityByBerichtId(berichtId: UUID): BerichtEntity? =
        find("berichtId", berichtId).firstResult()

    /**
     * Lichtgewicht variant van [findEntityByBerichtId] die alleen de surrogate
     * PK projecteert. Voor callers (bv. native `INSERT … ON CONFLICT`) die de
     * FK-waarde nodig hebben zonder de hele rij in de PersistenceContext te
     * willen laden.
     */
    internal fun findDbIdByBerichtId(berichtId: UUID): Long? =
        getEntityManager()
            .createQuery("SELECT b.id FROM BerichtEntity b WHERE b.berichtId = :id", Long::class.javaObjectType)
            .setParameter("id", berichtId)
            .resultList
            .firstOrNull()

    /**
     * Variant van [findByBerichtId] die ook soft-deleted berichten teruggeeft.
     * Bedoeld voor flows die idempotent moeten zijn over soft-delete heen, zoals
     * de DELETE-endpoint die de tweede aanroep door dezelfde ontvanger als 204
     * (geen verandering) moet beantwoorden.
     */
    fun findIncludingDeleted(berichtId: UUID): BerichtMetVerwijderdOp? =
        find("berichtId", berichtId).firstResult()?.let { entity ->
            BerichtMetVerwijderdOp(entity.toDomain(), entity.verwijderdOp)
        }

    /**
     * Paged lijst van actieve berichten voor een ontvanger, optioneel gefilterd
     * op afzender. Sortering: nieuwste bericht eerst — gebruikelijke UX in
     * berichtenbox-views.
     */
    fun lijstVoorOntvanger(
        ontvanger: Identificatienummer,
        afzender: String?,
        page: Int,
        pageSize: Int,
    ): PagedBerichten {
        val sort = Sort.by("tijdstipOntvangst", Sort.Direction.Descending)
        val query = if (afzender == null) {
            find(
                "ontvangerType = ?1 and ontvangerWaarde = ?2 and verwijderdOp is null",
                sort,
                ontvanger.type,
                ontvanger.waarde,
            )
        } else {
            find(
                "ontvangerType = ?1 and ontvangerWaarde = ?2 and afzender = ?3 and verwijderdOp is null",
                sort,
                ontvanger.type,
                ontvanger.waarde,
                afzender,
            )
        }
        val totaal = query.count()
        val items = query.page(Page.of(page, pageSize)).list().map { it.toDomain() }
        return PagedBerichten(
            berichten = items,
            page = page,
            pageSize = pageSize,
            totalElements = totaal,
        )
    }

    /**
     * Claimt een batch soft-deleted berichten die aan beide retentie-drempels
     * voldoen. Native query met `FOR UPDATE SKIP LOCKED` zodat parallelle pods
     * disjuncte rij-sets claimen. Caller MOET claim+delete binnen één
     * top-level transactie houden (rijen blijven gelockt totdat die commit).
     */
    fun claimVoorHardDelete(
        receiptDeadline: Instant,
        softDeleteDeadline: Instant,
        batchSize: Int,
    ): List<HardDeleteCandidaat> {
        @Suppress("UNCHECKED_CAST")
        val rijen = getEntityManager()
            .createNativeQuery(
                """
                SELECT id, bericht_id, ontvanger_type, ontvanger_waarde,
                       tijdstip_ontvangst, verwijderd_op
                FROM berichten
                WHERE verwijderd_op IS NOT NULL
                  AND verwijderd_op      <= :softDeadline
                  AND tijdstip_ontvangst <= :receiptDeadline
                ORDER BY verwijderd_op ASC
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
                """.trimIndent(),
            )
            .setParameter("softDeadline", softDeleteDeadline)
            .setParameter("receiptDeadline", receiptDeadline)
            .setParameter("batchSize", batchSize)
            .resultList as List<Array<Any?>>

        return rijen.map { row ->
            HardDeleteCandidaat(
                id = (row[0] as Number).toLong(),
                berichtId = row[1] as java.util.UUID,
                ontvangerType = row[2] as String,
                ontvangerWaarde = row[3] as String,
                // pgjdbc retourneert LocalDateTime voor TIMESTAMP WITHOUT TIME ZONE
                // (V1-schema). Hibernate slaat Instant op als UTC, dus terugconverteren
                // met ZoneOffset.UTC houdt round-trip stabiel.
                tijdstipOntvangst = (row[4] as java.time.LocalDateTime).toInstant(java.time.ZoneOffset.UTC),
                verwijderdOp = (row[5] as java.time.LocalDateTime).toInstant(java.time.ZoneOffset.UTC),
            )
        }
    }

    /**
     * Hard-delete van de bericht-rij op de surrogate PK. Caller MOET eerst de
     * child-rijen (bijlagen, status) verwijderen — FK is RESTRICT.
     */
    fun hardDeleteByDbId(berichtDbId: Long): Int =
        delete("id = ?1", berichtDbId).toInt()

    /**
     * Markeert een bericht als verwijderd voor de opgegeven ontvanger.
     * Soft-delete: zet `verwijderdOp` op het huidige tijdstip. Retourneert
     * `true` als precies één rij is bijgewerkt; `false` als het bericht niet
     * bestond of niet bij de ontvanger hoorde of al verwijderd was.
     *
     * Werpt [IllegalStateException] als meer dan één rij is bijgewerkt — de
     * `(berichtId, ontvanger)`-combinatie is uniek voor actieve berichten,
     * dus dat duidt op datacorruptie en mag niet stil falen.
     */
    fun softDelete(berichtId: UUID, ontvanger: Identificatienummer, tijdstip: Instant): Boolean {
        val rows = update(
            "verwijderdOp = ?1 where berichtId = ?2 and ontvangerType = ?3 and ontvangerWaarde = ?4 and verwijderdOp is null",
            tijdstip,
            berichtId,
            ontvanger.type,
            ontvanger.waarde,
        )
        check(rows <= 1) {
            "softDelete heeft $rows rijen gewijzigd voor berichtId=$berichtId — verwacht 0 of 1 (mogelijke datacorruptie)"
        }
        return rows == 1
    }
}

/**
 * Resultaat van [BerichtRepository.findIncludingDeleted]. Maakt het mogelijk om
 * voor idempotente flows onderscheid te maken tussen "bestaat niet", "bestaat
 * maar verwijderd" en "actief".
 */
data class BerichtMetVerwijderdOp(
    val bericht: Bericht,
    val verwijderdOp: Instant?,
) {
    val isVerwijderd: Boolean get() = verwijderdOp != null
}

/**
 * Resultaat van een gepagineerde berichten-query. Bevat alleen de data die de
 * service nodig heeft; de Resource bouwt hierop HAL-pagina-links op.
 */
data class PagedBerichten(
    val berichten: List<Bericht>,
    val page: Int,
    val pageSize: Int,
    val totalElements: Long,
) {
    init {
        require(page >= 0) { "page mag niet negatief zijn (kreeg $page)" }
        require(pageSize > 0) { "pageSize moet > 0 zijn (kreeg $pageSize)" }
        require(totalElements >= 0) { "totalElements mag niet negatief zijn (kreeg $totalElements)" }
    }

    val totalPages: Int = ((totalElements + pageSize - 1) / pageSize).toInt()
}
