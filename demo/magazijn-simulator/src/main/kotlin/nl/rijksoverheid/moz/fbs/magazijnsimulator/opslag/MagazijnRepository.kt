package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.Gedrag
import org.jboss.logging.Logger

/** Eén magazijn-rij zoals de rest van de applicatie hem nodig heeft. */
data class MagazijnRij(val dbId: Long, val oin: String, val naam: String, val gedrag: Gedrag)

/**
 * De magazijn-rijen: de tabel die alle andere tabellen discrimineert, en die het gedrag per magazijn
 * draagt.
 *
 * Gevuld vanuit de configuratie bij het starten, niet via een beheer-aanroep. Anders is er een
 * opstartvolgorde — tot die aanroep geeft elk pad 404 terwijl het register van de uitvraag geldig
 * oogt — én een tweede waarheid die kan driften.
 */
@ApplicationScoped
class MagazijnRepository : PanacheRepositoryBase<MagazijnEntity, Long> {

    private val log = Logger.getLogger(MagazijnRepository::class.java)

    /**
     * Brengt de tabel in overeenstemming met de geconfigureerde set en geeft alle rijen terug.
     *
     * Ontbrekende magazijnen worden aangemaakt, en naam én gedrag worden bijgewerkt: de configuratie
     * is de bron, dus een herstart zet een tijdens een demo bijgestelde storing weer terug op de
     * vastgelegde verdeling. Dat is bedoeld gedrag — "terug naar de begintoestand" hoort ook het
     * gedrag te omvatten en niet alleen de berichten.
     *
     * Rijen die niet meer in de configuratie staan blijven staan: hun berichten hangen eraan met een
     * RESTRICT-FK, en ze zijn toch onbereikbaar omdat het pad-filter alleen geconfigureerde OIN's
     * doorlaat. Wel een waarschuwing, want stil data laten rondslingeren is hoe een demo per ongeluk
     * oude berichten toont.
     */
    @Transactional
    fun brengInOvereenstemming(gewenst: Map<String, Paar>): List<MagazijnRij> {
        val bestaand = listAll().associateBy { it.oin }

        gewenst.forEach { (oin, instelling) ->
            val rij = bestaand[oin] ?: MagazijnEntity().also { nieuw ->
                nieuw.oin = oin
                persist(nieuw)
            }

            rij.naam = instelling.naam
            rij.zet(instelling.gedrag)
        }

        val verweesd = bestaand.keys - gewenst.keys

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

        // Alleen wat gevraagd is. Zou een verweesde rij hier meekomen, dan bleef een magazijn dat
        // uit de configuratie is gehaald gewoon bereikbaar — terwijl het register van de uitvraag
        // hem niet meer kent.
        return listAll()
            .filter { it.oin in gewenst }
            .map { MagazijnRij(dbId = it.id, oin = it.oin, naam = it.naam, gedrag = it.gedrag()) }
    }

    /** Stelt het gedrag van één magazijn bij; `false` als die OIN niet bestaat. */
    @Transactional
    fun zetGedrag(oin: String, gedrag: Gedrag): Boolean {
        val rij = find("oin", oin).firstResult() ?: return false

        rij.zet(gedrag)

        return true
    }

    /**
     * De entity-referentie voor een FK, zonder de rij te laden. `getReference` levert een proxy —
     * genoeg om een `magazijn_db_id` te zetten, en het scheelt een select per aanlevering.
     */
    internal fun referentie(magazijnDbId: Long): MagazijnEntity =
        getEntityManager().getReference(MagazijnEntity::class.java, magazijnDbId)

    /** Naam plus gedrag van één magazijn, zoals de configuratie het voorschrijft. */
    data class Paar(val naam: String, val gedrag: Gedrag)
}
