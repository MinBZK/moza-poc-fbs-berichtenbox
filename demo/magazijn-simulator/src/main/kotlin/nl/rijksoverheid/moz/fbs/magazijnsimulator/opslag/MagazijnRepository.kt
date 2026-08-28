package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

/**
 * De magazijn-rijen: de tabel die alle andere tabellen discrimineert.
 *
 * Gevuld vanuit de configuratie bij het starten, niet via een beheer-aanroep. Anders is er een
 * opstartvolgorde — tot die aanroep geeft elk pad 404 terwijl het register van de uitvraag geldig
 * oogt — én een tweede waarheid die kan driften.
 */
@ApplicationScoped
class MagazijnRepository : PanacheRepositoryBase<MagazijnEntity, Long> {

    private val log = Logger.getLogger(MagazijnRepository::class.java)

    /**
     * Brengt de tabel in overeenstemming met de geconfigureerde set en geeft de database-id per OIN
     * terug. Die id draagt de request-context daarna mee, zodat geen enkele query hem eerst hoeft op
     * te zoeken — bij een fan-out van honderd is dat honderd bespaarde rondjes.
     *
     * Ontbrekende magazijnen worden aangemaakt en gewijzigde namen bijgewerkt. Rijen die niet meer
     * in de configuratie staan blijven staan: hun berichten hangen eraan met een RESTRICT-FK, en ze
     * zijn toch onbereikbaar omdat het pad-filter alleen geconfigureerde OIN's doorlaat. Wel een
     * waarschuwing, want stil data laten rondslingeren is hoe een demo per ongeluk oude berichten
     * toont.
     */
    @Transactional
    fun brengInOvereenstemming(naamPerOin: Map<String, String>): Map<String, Long> {
        val bestaand = listAll().associateBy { it.oin }

        naamPerOin.forEach { (oin, naam) ->
            val rij = bestaand[oin]

            if (rij == null) {
                persist(MagazijnEntity().apply { this.oin = oin; this.naam = naam })
            } else if (rij.naam != naam) {
                rij.naam = naam
            }
        }

        val verweesd = bestaand.keys - naamPerOin.keys

        if (verweesd.isNotEmpty()) {
            log.warnf(
                "%d magazijn(en) staan in de database maar niet meer in de configuratie en zijn dus " +
                    "onbereikbaar; hun berichten blijven staan. OIN's: %s",
                verweesd.size,
                verweesd.sorted(),
            )
        }

        // Opnieuw lezen in plaats van de zojuist gepersisteerde entities gebruiken: de id van een
        // nieuwe rij komt pas bij de flush uit de database.
        flush()

        return listAll().associate { it.oin to it.id }
    }

    /**
     * De entity-referentie voor een FK, zonder de rij te laden. `getReference` levert een proxy —
     * genoeg om een `magazijn_db_id` te zetten, en het scheelt een select per aanlevering.
     */
    internal fun referentie(magazijnDbId: Long): MagazijnEntity =
        getEntityManager().getReference(MagazijnEntity::class.java, magazijnDbId)
}
