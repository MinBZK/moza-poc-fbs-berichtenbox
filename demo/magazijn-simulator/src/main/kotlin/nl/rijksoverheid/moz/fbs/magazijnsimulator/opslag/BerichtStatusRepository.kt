package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * De leesstatus per bericht. Een ontbrekende rij betekent "de ontvanger heeft nog niets gezet" —
 * precies het onderscheid dat de spec maakt door `status` dan wég te laten.
 *
 * De discriminator zit hier indirect: elke methode neemt bericht-database-id's die de aanroeper al
 * per magazijn heeft opgezocht. Een status-rij hangt aan één bericht, en dat bericht hangt aan één
 * magazijn.
 */
@ApplicationScoped
class BerichtStatusRepository : PanacheRepositoryBase<BerichtStatusEntity, Long> {

    /** De statussen van een hele pagina berichten in één query, tegen N+1 op de lijst. */
    fun voorBerichten(berichtDbIds: List<Long>): Map<Long, BerichtStatus> {
        if (berichtDbIds.isEmpty()) return emptyMap()

        return find("bericht.id in ?1", berichtDbIds)
            .list()
            .associate { rij ->
                rij.bericht.id to BerichtStatus(
                    gelezen = rij.gelezen,
                    map = rij.map,
                    gewijzigdOp = rij.gewijzigdOp,
                )
            }
    }

    /**
     * Past een wijziging toe, en maakt de rij aan als hij nog niet bestond.
     *
     * **In één opdracht, niet lezen-dan-schrijven.** Twee `PATCH`-verzoeken die tegelijk binnenkomen
     * op een bericht zonder status-rij — twee tabbladen, een dubbelklik, een retry na een hikje —
     * zouden allebei zien dat er niets staat en allebei een rij willen aanmaken. De tweede loopt dan
     * tegen de unique-constraint en eindigt als 500, terwijl het echte magazijn daar gewoon 200
     * geeft. `ON CONFLICT DO UPDATE` maakt er één atomaire stap van.
     *
     * De `COALESCE` draagt de merge-patch-semantiek van de spec: een veld dat `null` is blijft
     * ongewijzigd — of het nu ontbrak in de JSON of expliciet op `null` stond. Een map is daarmee te
     * overschrijven maar niet te wissen; dat is dezelfde beperking die het echte magazijn heeft, en
     * de simulator hoort hem te delen. Bij een nieuwe rij valt `gelezen` terug op `false` en niet op
     * de patch-waarde: wie alleen een map zet, heeft het bericht nog niet gelezen.
     *
     * De casts zijn nodig omdat PostgreSQL het type van een parameter die `null` kan zijn niet uit
     * de context kan afleiden.
     */
    fun pasToe(berichtDbId: Long, wijziging: BerichtStatusWijziging, tijdstip: Instant) {
        // Openstaande wijzigingen eerst wegschrijven: deze native opdracht gaat langs de
        // persistence-context om, en zou anders een rij kunnen missen die nog in de sessie hangt.
        flush()

        getEntityManager()
            .createNativeQuery(
                """
                INSERT INTO bericht_status (bericht_db_id, gelezen, map, gewijzigd_op)
                VALUES (:berichtDbId, COALESCE(CAST(:gelezen AS BOOLEAN), FALSE), CAST(:map AS VARCHAR), :tijdstip)
                ON CONFLICT (bericht_db_id) DO UPDATE
                SET gelezen      = COALESCE(CAST(:gelezen AS BOOLEAN), bericht_status.gelezen),
                    map          = COALESCE(CAST(:map AS VARCHAR), bericht_status.map),
                    gewijzigd_op = :tijdstip
                """.trimIndent(),
            )
            .setParameter("berichtDbId", berichtDbId)
            .setParameter("gelezen", wijziging.gelezen)
            .setParameter("map", wijziging.map)
            .setParameter("tijdstip", tijdstip)
            .executeUpdate()

        // De native opdracht is buiten de persistence-context om gegaan; zonder deze opruiming zou
        // een volgende lees-actie in dezelfde transactie de oude rij uit de sessie kunnen teruggeven.
        getEntityManager().clear()
    }
}
