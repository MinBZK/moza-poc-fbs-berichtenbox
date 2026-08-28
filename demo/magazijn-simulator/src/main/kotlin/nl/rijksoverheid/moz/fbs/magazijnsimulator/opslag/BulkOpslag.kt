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
 */
@ApplicationScoped
class BulkOpslag(private val entityManager: EntityManager) {

    /** Voegt berichten met hun bijlagen toe. Geeft terug hoeveel er van elk zijn weggeschreven. */
    @Transactional
    fun voegToe(magazijnDbId: Long, berichten: List<BulkBericht>): BulkUitkomst {
        if (berichten.isEmpty()) return BulkUitkomst(0, 0)

        var bijlagen = 0

        berichten.chunked(BLOKGROOTTE).forEach { blok ->
            val dbIdPerBerichtId = schrijfBerichten(magazijnDbId, blok.map { it.bericht })

            bijlagen += schrijfBijlagen(blok, dbIdPerBerichtId)
        }

        return BulkUitkomst(berichten = berichten.size, bijlagen = bijlagen)
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

    private fun schrijfBijlagen(blok: List<BulkBericht>, dbIdPerBerichtId: Map<UUID, Long>): Int {
        val paren = blok.flatMap { item ->
            val berichtDbId = checkNotNull(dbIdPerBerichtId[item.bericht.berichtId]) {
                "Bericht ${item.bericht.berichtId} kreeg geen database-id terug"
            }

            item.bijlagen.map { berichtDbId to it }
        }

        if (paren.isEmpty()) return 0

        val rijen = paren.indices.joinToString(", ") { i -> "(:r$i, :i$i, :n$i, :m$i, :c$i)" }
        val query = entityManager.createNativeQuery(
            "INSERT INTO bijlage (bericht_db_id, bijlage_id, naam, mime_type, inhoud) VALUES $rijen",
        )

        paren.forEachIndexed { i, (berichtDbId, bijlage) ->
            query.setParameter("r$i", berichtDbId)
            query.setParameter("i$i", bijlage.bijlageId)
            query.setParameter("n$i", bijlage.naam)
            query.setParameter("m$i", bijlage.mimeType)
            query.setParameter("c$i", bijlage.inhoud)
        }

        query.executeUpdate()

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

/** Hoeveel er is weggeschreven. */
data class BulkUitkomst(val berichten: Int, val bijlagen: Int)
