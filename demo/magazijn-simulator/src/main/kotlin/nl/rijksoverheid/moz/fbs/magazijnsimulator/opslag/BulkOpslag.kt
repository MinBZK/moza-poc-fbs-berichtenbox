package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.sql.Timestamp
import java.util.UUID

/** Wat er in één keer weggeschreven moet worden voor één magazijn. */
data class BulkBericht(val bericht: Bericht, val bijlagen: List<Bijlage>)

/**
 * Schrijft een hele demo in één transactie weg.
 *
 * **Waarom niet gewoon de gewone opslag per bericht?** Omdat het dan minuten duurt. De tabel gebruikt
 * een door de database gegenereerde sleutel, en daarop schakelt Hibernate zijn JDBC-batching uit: elk
 * bericht wordt een eigen rondje naar de database. Bij honderd magazijnen maal vier ondernemers maal
 * twintig berichten zijn dat achtduizend rondjes, en een demo voorbereiden hoort seconden te kosten,
 * geen koffiepauze.
 *
 * Vandaar één `INSERT` met veel rijen tegelijk, in blokken. `RETURNING` levert de nieuwe sleutels
 * terug zodat de bijlagen eraan gehangen kunnen worden; de koppeling gaat via het `berichtId` en niet
 * via de volgorde van de resultaten, want die volgorde belooft PostgreSQL nergens.
 *
 * **Twee keer vullen mag.** De bericht-nummers zijn afgeleid, dus een tweede ronde biedt exact
 * dezelfde rijen aan en zou op de unique-constraint stuklopen. `ON CONFLICT DO NOTHING` maakt er een
 * herhaalbare handeling van: wie tijdens de voorbereiding besluit dat twintig berichten te weinig
 * zijn, draait gewoon opnieuw. Zonder dat zou hij een 500 krijgen waarin niets staat over de oorzaak
 * of over de uitweg — en zou een run die halverwege afbrak alleen nog met `legen` te herstellen zijn.
 */
@ApplicationScoped
class BulkOpslag(private val entityManager: EntityManager) {

    /**
     * Voegt berichten met hun bijlagen toe, en slaat over wat er al staat. Geeft terug hoeveel er
     * daadwerkelijk bij kwam en hoeveel er al was — dat verschil zichtbaar maken is beter dan een
     * tweede ronde die "gelukt" meldt zonder dat er iets veranderde.
     */
    @Transactional
    fun voegToe(magazijnDbId: Long, berichten: List<BulkBericht>): BulkUitkomst {
        if (berichten.isEmpty()) return BulkUitkomst(0, 0, 0)

        var toegevoegd = 0
        var bijlagen = 0

        berichten.chunked(BLOKGROOTTE).forEach { blok ->
            val dbIdPerBerichtId = schrijfBerichten(magazijnDbId, blok.map { it.bericht })

            toegevoegd += dbIdPerBerichtId.size
            bijlagen += schrijfBijlagen(blok, dbIdPerBerichtId)
        }

        return BulkUitkomst(
            berichten = toegevoegd,
            bijlagen = bijlagen,
            overgeslagen = berichten.size - toegevoegd,
        )
    }

    /** Verwijdert alle berichten van alle magazijnen; child-eerst, want de FK's staan op RESTRICT. */
    @Transactional
    fun leegAlleBerichten(): Int {
        entityManager.createNativeQuery("DELETE FROM bericht_status").executeUpdate()
        entityManager.createNativeQuery("DELETE FROM bijlage").executeUpdate()

        return entityManager.createNativeQuery("DELETE FROM bericht").executeUpdate()
    }

    private fun schrijfBerichten(magazijnDbId: Long, berichten: List<Bericht>): Map<UUID, Long> {
        val rijen = berichten.indices.joinToString(", ") { i ->
            "(:m, :b$i, :a$i, :ot$i, :ow$i, :on$i, :ih$i, :to$i, :pt$i)"
        }

        val query = entityManager.createNativeQuery(
            """
            INSERT INTO bericht (magazijn_db_id, bericht_id, afzender, ontvanger_type, ontvanger_waarde,
                                 onderwerp, inhoud, tijdstip_ontvangst, publicatietijdstip)
            VALUES $rijen
            ON CONFLICT (magazijn_db_id, bericht_id) DO NOTHING
            RETURNING id, bericht_id
            """.trimIndent(),
        )

        query.setParameter("m", magazijnDbId)

        berichten.forEachIndexed { i, bericht ->
            query.setParameter("b$i", bericht.berichtId)
            query.setParameter("a$i", bericht.afzender.waarde)
            query.setParameter("ot$i", bericht.ontvanger.type.name)
            query.setParameter("ow$i", bericht.ontvanger.waarde)
            query.setParameter("on$i", bericht.onderwerp)
            query.setParameter("ih$i", bericht.inhoud)
            query.setParameter("to$i", Timestamp.from(bericht.tijdstipOntvangst))
            query.setParameter("pt$i", Timestamp.from(bericht.publicatietijdstip))
        }

        @Suppress("UNCHECKED_CAST")
        val uitkomst = query.resultList as List<Array<Any>>

        return uitkomst.associate { (it[1] as UUID) to (it[0] as Number).toLong() }
    }

    /**
     * Bijlagen bij de berichten die zojuist zijn toegevoegd. Berichten die al bestonden komen niet in
     * [dbIdPerBerichtId] voor en worden overgeslagen: hun bijlagen staan er al, en ze opnieuw
     * aanbieden zou op de unique-constraint stuklopen.
     *
     * In blokken, net als de berichten. De aanroeper bepaalt hoeveel bijlagen er per bericht zijn, en
     * met vijf parameters per rij loopt PostgreSQL's grens van 65535 anders vanaf ongeveer
     * zevenentwintig bijlagen per bericht vol.
     */
    private fun schrijfBijlagen(blok: List<BulkBericht>, dbIdPerBerichtId: Map<UUID, Long>): Int {
        val paren = blok.flatMap { item ->
            val berichtDbId = dbIdPerBerichtId[item.bericht.berichtId]

            if (berichtDbId == null) emptyList() else item.bijlagen.map { berichtDbId to it }
        }

        paren.chunked(BLOKGROOTTE).forEach { deel ->
            val rijen = deel.indices.joinToString(", ") { i -> "(:r$i, :i$i, :n$i, :m$i, :c$i)" }
            val query = entityManager.createNativeQuery(
                "INSERT INTO bijlage (bericht_db_id, bijlage_id, naam, mime_type, inhoud) VALUES $rijen",
            )

            deel.forEachIndexed { i, (berichtDbId, bijlage) ->
                query.setParameter("r$i", berichtDbId)
                query.setParameter("i$i", bijlage.bijlageId)
                query.setParameter("n$i", bijlage.naam)
                query.setParameter("m$i", bijlage.mimeType)
                query.setParameter("c$i", bijlage.inhoud)
            }

            query.executeUpdate()
        }

        return paren.size
    }

    private companion object {
        /**
         * Hoeveel berichten er in één `INSERT` gaan. PostgreSQL staat 65535 parameters per opdracht
         * toe; met negen parameters per bericht past dit ruim, en groter maken levert weinig meer op.
         */
        const val BLOKGROOTTE = 500
    }
}

/** Hoeveel er is weggeschreven, en hoeveel er al stond. */
data class BulkUitkomst(val berichten: Int, val bijlagen: Int, val overgeslagen: Int)
