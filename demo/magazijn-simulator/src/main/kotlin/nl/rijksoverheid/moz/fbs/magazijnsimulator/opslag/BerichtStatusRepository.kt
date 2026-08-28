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
     * Merge-patch-semantiek van de spec: een veld dat `null` is blijft ongewijzigd — of het nu
     * ontbrak in de JSON of expliciet op `null` stond. Een map is daarmee te overschrijven maar niet
     * te wissen; dat is dezelfde beperking die het echte magazijn heeft, en de simulator hoort hem
     * te delen.
     */
    internal fun pasToe(bericht: BerichtEntity, wijziging: BerichtStatusWijziging, tijdstip: Instant) {
        val bestaand = find("bericht.id = ?1", bericht.id).firstResult()

        if (bestaand == null) {
            persist(
                BerichtStatusEntity().apply {
                    this.bericht = bericht
                    gelezen = wijziging.gelezen ?: false
                    map = wijziging.map
                    gewijzigdOp = tijdstip
                },
            )

            return
        }

        wijziging.gelezen?.let { bestaand.gelezen = it }
        wijziging.map?.let { bestaand.map = it }
        // Ook als de waardes gelijk blijven: de ontvanger heeft het bericht aangeraakt, en dát is
        // wat `gewijzigdOp` uitdrukt.
        bestaand.gewijzigdOp = tijdstip
    }
}
