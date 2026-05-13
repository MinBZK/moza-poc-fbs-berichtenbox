package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
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
     * Markeert een bericht als verwijderd voor de opgegeven ontvanger.
     * Soft-delete: zet `verwijderdOp` op het huidige tijdstip. Retourneert
     * `true` als precies één rij is bijgewerkt; `false` als het bericht niet
     * bestond of niet bij de ontvanger hoorde of al verwijderd was.
     */
    fun softDelete(berichtId: UUID, ontvanger: Identificatienummer, tijdstip: Instant): Boolean {
        val rows = update(
            "verwijderdOp = ?1 where berichtId = ?2 and ontvangerType = ?3 and ontvangerWaarde = ?4 and verwijderdOp is null",
            tijdstip,
            berichtId,
            ontvanger.type,
            ontvanger.waarde,
        )
        return rows == 1
    }
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
    val totalPages: Int = if (pageSize <= 0) 0
        else ((totalElements + pageSize - 1) / pageSize).toInt()
}
