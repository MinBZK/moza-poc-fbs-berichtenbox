package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Tuple
import java.util.UUID

/**
 * Panache-repository voor bijlagen. Externe code werkt uitsluitend met [Bijlage]
 * en [BijlageMetadata] — [BijlageEntity] is een `internal` implementatiedetail.
 *
 * De FK naar `berichten` loopt via de surrogate PK (`bericht_db_id` → `berichten.id`).
 * [BerichtRepository] levert de [BerichtEntity]-referentie op basis van de
 * business-key zodat callers van [save] alsnog met de UUID kunnen werken.
 */
@ApplicationScoped
class BijlageRepository(
    private val berichtRepository: BerichtRepository,
) : PanacheRepositoryBase<BijlageEntity, Long> {

    /**
     * Persisteert een nieuwe bijlage. Wordt aangeroepen vanuit de Aanlever-flow
     * voor elke bijlage in het aangeleverde bericht. Faalt met `IllegalArgumentException`
     * als het parent-bericht (nog) niet bestaat — de Aanlever-service plaatst de
     * bericht-save in dezelfde transactie, dus dat scenario duidt op een bug.
     */
    fun save(bijlage: Bijlage) {
        val berichtEntity = berichtRepository.findEntityByBerichtId(bijlage.berichtId)
            ?: throw IllegalArgumentException(
                "Bericht niet gevonden voor berichtId=${bijlage.berichtId}",
            )
        persist(
            BijlageEntity().apply {
                bijlageId = bijlage.bijlageId
                bericht = berichtEntity
                naam = bijlage.naam
                mimeType = bijlage.mimeType
                content = bijlage.content
            },
        )
    }

    /**
     * Haalt één bijlage op (incl. bytes) aan de hand van bericht- én bijlage-ID.
     * Beide IDs moeten matchen, anders 404 — dit voorkomt dat een gokje op een
     * `bijlageId` resultaat geeft bij een willekeurig ander bericht. Verwijderde
     * berichten worden uitgesloten (defense-in-depth: callers controleren al,
     * maar deze query mag onafhankelijk veilig zijn).
     */
    fun findByBerichtIdEnBijlageId(berichtId: UUID, bijlageId: UUID): Bijlage? =
        find(
            "bericht.berichtId = ?1 and bijlageId = ?2 and bericht.verwijderdOp is null",
            berichtId,
            bijlageId,
        ).firstResult()?.toBijlage(berichtId)

    /**
     * Haalt alleen de metadata (zonder bytes) op van alle bijlagen bij een bericht.
     * Gebruikt door `GET /berichten/{id}` om bijlage-metadata in de response te
     * bouwen. Projection-query zodat de `content`-kolom (`BYTEA`, tot 25 MiB per
     * bijlage) niet onnodig in heap belandt. Sluit verwijderde berichten uit.
     */
    fun metadataVoorBericht(berichtId: UUID): List<BijlageMetadata> =
        getEntityManager()
            .createQuery(
                """
                SELECT new nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageMetadata(
                    b.bijlageId, b.naam, b.mimeType
                )
                FROM BijlageEntity b
                WHERE b.bericht.berichtId = :berichtId
                  AND b.bericht.verwijderdOp IS NULL
                """.trimIndent(),
                BijlageMetadata::class.java,
            )
            .setParameter("berichtId", berichtId)
            .resultList

    /**
     * Batch-variant voor lijst-endpoints: laadt bijlage-metadata voor een
     * verzameling berichten in één query (anders N+1). Geretourneerd als map
     * `berichtId → metadata-lijst`; berichten zonder bijlagen ontbreken in de
     * map en moeten door de caller behandeld worden als lege lijst.
     *
     * De query gebruikt [Tuple] met benoemde aliassen ([BERICHT_ID_ALIAS],
     * [METADATA_ALIAS]) zodat de mapping niet leunt op positionele
     * [Array]-casts: bij JPQL- of entity-drift komt er een duidelijke fout
     * uit Hibernate i.p.v. een onbegrijpelijke `ClassCastException` → 500.
     */
    fun metadataVoorBerichten(berichtIds: Collection<UUID>): Map<UUID, List<BijlageMetadata>> {
        if (berichtIds.isEmpty()) return emptyMap()
        return getEntityManager()
            .createQuery(
                """
                SELECT b.bericht.berichtId AS $BERICHT_ID_ALIAS,
                       new nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageMetadata(
                           b.bijlageId, b.naam, b.mimeType
                       ) AS $METADATA_ALIAS
                FROM BijlageEntity b
                WHERE b.bericht.berichtId IN :ids
                  AND b.bericht.verwijderdOp IS NULL
                """.trimIndent(),
                Tuple::class.java,
            )
            .setParameter("ids", berichtIds)
            .resultList
            .groupBy(
                keySelector = { it.get(BERICHT_ID_ALIAS, UUID::class.java) },
                valueTransform = { it.get(METADATA_ALIAS, BijlageMetadata::class.java) },
            )
    }

    private companion object {
        private const val BERICHT_ID_ALIAS = "berichtId"
        private const val METADATA_ALIAS = "metadata"
    }
}

// `berichtId` als parameter zodat we de LAZY `bericht`-associatie niet hoeven
// te dereferentiëren — caller kent de business-key al, en zo voorkomen we
// een LazyInitializationException als de Bijlage buiten transactie wordt
// teruggegeven of gedetacheerd raakt.
private fun BijlageEntity.toBijlage(berichtId: UUID): Bijlage = Bijlage(
    bijlageId = bijlageId,
    berichtId = berichtId,
    naam = naam,
    mimeType = mimeType,
    content = content,
)
