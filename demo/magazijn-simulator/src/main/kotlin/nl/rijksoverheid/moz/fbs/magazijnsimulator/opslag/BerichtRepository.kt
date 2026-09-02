package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Berichten van één gesimuleerd magazijn.
 *
 * **Elke methode neemt `magazijnDbId` als eerste parameter en gebruikt hem in de `WHERE`.** Er
 * hoort in dit bestand geen query te staan die dat niet doet: één vergeten discriminator en het ene
 * gesimuleerde magazijn levert de berichten van het andere — een lek dat in een demo met honderd
 * magazijnen nergens uit op te maken is.
 */
@ApplicationScoped
class BerichtRepository(
    private val magazijnen: MagazijnRepository,
    private val statussen: BerichtStatusRepository,
    private val bijlagen: BijlageRepository,
) : PanacheRepositoryBase<BerichtEntity, Long> {

    /** Slaat een bericht met zijn bijlagen op in één transactie. */
    @Transactional
    fun bewaar(magazijnDbId: Long, bericht: Bericht, inhoudPerBijlage: List<Bijlage>) {
        val entity = BerichtEntity().apply {
            magazijn = magazijnen.referentie(magazijnDbId)
            berichtId = bericht.berichtId
            afzender = bericht.afzender.waarde
            ontvangerType = bericht.ontvanger.type
            ontvangerWaarde = bericht.ontvanger.waarde
            onderwerp = bericht.onderwerp
            inhoud = bericht.inhoud
            tijdstipOntvangst = bericht.tijdstipOntvangst
            publicatietijdstip = bericht.publicatietijdstip
        }

        persist(entity)
        bijlagen.bewaar(entity, inhoudPerBijlage)
    }

    /**
     * Eén pagina actieve berichten voor een ontvanger, nieuwste eerst.
     *
     * De status- en bijlage-gegevens worden in twee extra queries voor de héle pagina opgehaald in
     * plaats van per bericht: bij twintig berichten scheelt dat achtendertig rondjes naar de
     * database, en dat is precies het soort kosten dat bij een fan-out van honderd zichtbaar wordt.
     */
    fun lijst(
        magazijnDbId: Long,
        ontvanger: Identificatie,
        afzender: String?,
        page: Int,
        pageSize: Int,
    ): BerichtenPagina {
        // De database-id als tweede sleutel, en niet alleen het tijdstip. Twee aanleveringen binnen
        // dezelfde klok-tik krijgen hetzelfde tijdstip, en dan is de volgorde zonder tiebreaker aan
        // de database: bij paginering kan een bericht daardoor op twee pagina's staan of op geen.
        // De id loopt op met de aanlevering, dus aflopend is dezelfde bedoeling als "nieuwste eerst".
        val sortering = Sort.by("tijdstipOntvangst", Sort.Direction.Descending)
            .and("id", Sort.Direction.Descending)
        val query = if (afzender == null) {
            find(
                "magazijn.id = ?1 and ontvangerType = ?2 and ontvangerWaarde = ?3 and verwijderdOp is null",
                sortering,
                magazijnDbId,
                ontvanger.type,
                ontvanger.waarde,
            )
        } else {
            find(
                "magazijn.id = ?1 and ontvangerType = ?2 and ontvangerWaarde = ?3 and afzender = ?4 " +
                    "and verwijderdOp is null",
                sortering,
                magazijnDbId,
                ontvanger.type,
                ontvanger.waarde,
                afzender,
            )
        }

        val totaal = query.count()
        val rijen = query.page(Page.of(page, pageSize)).list()

        return BerichtenPagina(
            berichten = verrijk(rijen),
            page = page,
            pageSize = pageSize,
            totalElements = totaal,
        )
    }

    /** Een actief bericht met zijn status en bijlage-metadata, of `null`. */
    fun zoek(magazijnDbId: Long, berichtId: UUID): Bericht? =
        zoekEntity(magazijnDbId, berichtId)
            ?.takeIf { it.verwijderdOp == null }
            ?.let { verrijk(listOf(it)).first() }

    /**
     * Een bericht inclusief soft-deleted, met de verwijder-markering erbij. De aanroeper heeft dat
     * onderscheid nodig om 403 en 404 in de goede volgorde te kunnen geven: bestaan van andermans
     * bericht mag niet uit een statuscode af te leiden zijn.
     */
    fun zoekInclusiefVerwijderd(magazijnDbId: Long, berichtId: UUID): BerichtMetVerwijderdOp? =
        zoekEntity(magazijnDbId, berichtId)?.let { rij ->
            BerichtMetVerwijderdOp(verrijk(listOf(rij)).first(), rij.verwijderdOp)
        }

    /** De bijlage-bytes, maar alleen bij een actief bericht van dit magazijn. */
    fun zoekBijlage(magazijnDbId: Long, berichtId: UUID, bijlageId: UUID): Bijlage? {
        val bericht = zoekEntity(magazijnDbId, berichtId)?.takeIf { it.verwijderdOp == null } ?: return null

        return bijlagen.zoek(bericht.id, bijlageId)
    }

    /**
     * Markeert een bericht als verwijderd. Geeft `false` terug als er niets te verwijderen viel —
     * het bericht bestond niet in dit magazijn, hoorde bij een andere ontvanger, of was al weg.
     */
    @Transactional
    fun softDelete(magazijnDbId: Long, berichtId: UUID, ontvanger: Identificatie, tijdstip: Instant): Boolean {
        val rijen = update(
            "verwijderdOp = ?1 where magazijn.id = ?2 and berichtId = ?3 and ontvangerType = ?4 " +
                "and ontvangerWaarde = ?5 and verwijderdOp is null",
            tijdstip,
            magazijnDbId,
            berichtId,
            ontvanger.type,
            ontvanger.waarde,
        )

        // `(magazijn, berichtId)` is uniek, dus meer dan één rij kan alleen bij datacorruptie. Stil
        // doorgaan zou betekenen dat er berichten van iemand anders zijn meeverwijderd.
        check(rijen <= 1) {
            "softDelete raakte $rijen rijen voor berichtId=$berichtId in magazijn $magazijnDbId — verwacht 0 of 1"
        }

        return rijen == 1
    }

    /** Zet of werkt de status bij; `null` als het bericht niet (meer) in dit magazijn bestaat. */
    @Transactional
    fun wijzigStatus(
        magazijnDbId: Long,
        berichtId: UUID,
        wijziging: BerichtStatusWijziging,
        tijdstip: Instant,
    ): Bericht? {
        val rij = zoekEntity(magazijnDbId, berichtId)?.takeIf { it.verwijderdOp == null } ?: return null
        val dbId = rij.id

        statussen.pasToe(dbId, wijziging, tijdstip)

        // Het bericht opnieuw opzoeken: `pasToe` heeft de persistence-context geleegd, dus de eerder
        // geladen entity is losgekoppeld en zou geen verse status opleveren.
        return zoekEntity(magazijnDbId, berichtId)?.let { verrijk(listOf(it)).first() }
    }

    internal fun zoekEntity(magazijnDbId: Long, berichtId: UUID): BerichtEntity? =
        find("magazijn.id = ?1 and berichtId = ?2", magazijnDbId, berichtId).firstResult()

    private fun verrijk(rijen: List<BerichtEntity>): List<Bericht> {
        if (rijen.isEmpty()) return emptyList()

        val ids = rijen.map { it.id }
        val statusPerBericht = statussen.voorBerichten(ids)
        val bijlagenPerBericht = bijlagen.metadataVoorBerichten(ids)

        return rijen.map { rij ->
            Bericht(
                berichtId = rij.berichtId,
                afzender = Identificatie(IdentificatieType.OIN, rij.afzender),
                ontvanger = Identificatie(rij.ontvangerType, rij.ontvangerWaarde),
                onderwerp = rij.onderwerp,
                inhoud = rij.inhoud,
                tijdstipOntvangst = rij.tijdstipOntvangst,
                publicatietijdstip = rij.publicatietijdstip,
                bijlagen = bijlagenPerBericht[rij.id].orEmpty(),
                status = statusPerBericht[rij.id],
            )
        }
    }
}

/**
 * Een bericht plus de vraag of het al verwijderd is. Nodig om "bestaat niet", "bestaat maar is
 * verwijderd" en "actief" uit elkaar te kunnen houden.
 */
data class BerichtMetVerwijderdOp(val bericht: Bericht, val verwijderdOp: Instant?) {
    val isVerwijderd: Boolean get() = verwijderdOp != null
}
