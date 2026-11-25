package nl.rijksoverheid.moz.fbs.common.logboek

import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant

/**
 * Leest de logregels van één betrokkene uit de logboektabel die de wrapper schrijft. De wrapper
 * kan zelf alleen schrijven; elke dienst levert hier de verbinding naar zijn eigen logboek.
 */
object LogboekLezing {

    private const val ONBEKENDE_TABEL = "42P01"
    private val TABELNAAM = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$")

    /**
     * [berichtIdsVanBetrokkene] is optioneel een subquery met twee parameters (type en waarde van
     * de betrokkene) die bericht-id's als tekst oplevert. Daarmee tellen ook de regels mee die het
     * logboek op het bericht-id vastlegde (`BERICHT_ID_ONLY`). Hij komt uit code, nooit uit invoer.
     *
     * Bestaat de tabel nog niet (de wrapper maakt hem pas bij de eerste logregel aan), dan is het
     * resultaat leeg.
     */
    fun lees(
        connection: Connection,
        tabel: String,
        betrokkene: Identificatienummer,
        van: Instant?,
        tot: Instant?,
        berichtIdsVanBetrokkene: String? = null,
    ): List<LogboekRij> {
        // De tabelnaam komt uit configuratie en gaat letterlijk de SQL in.
        require(TABELNAAM.matches(tabel)) { "Ongeldige logboektabelnaam" }

        val sql = sql(tabel, van != null, tot != null, berichtIdsVanBetrokkene)

        return try {
            connection.prepareStatement(sql).use { statement ->
                var i = 0
                statement.setString(++i, betrokkene.type.name)
                statement.setString(++i, betrokkene.waarde)

                if (berichtIdsVanBetrokkene != null) {
                    statement.setString(++i, betrokkene.type.name)
                    statement.setString(++i, betrokkene.waarde)
                }

                van?.let { statement.setLong(++i, it.toEpochMilli()) }
                tot?.let { statement.setLong(++i, it.toEpochMilli()) }

                statement.executeQuery().use { resultaat ->
                    buildList { while (resultaat.next()) add(naarRij(resultaat)) }
                }
            }
        } catch (ex: SQLException) {
            if (ex.sqlState == ONBEKENDE_TABEL) emptyList() else throw ex
        }
    }

    internal fun sql(tabel: String, metVan: Boolean, metTot: Boolean, berichtIdsVanBetrokkene: String?): String {
        val viaBerichtId = berichtIdsVanBetrokkene?.let {
            """
                 OR (attributes->>'dpl.core.data_subject_id_type' = 'BERICHT_ID_ONLY'
                     AND attributes->>'dpl.core.data_subject_id' IN ($it))
            """.trimEnd()
        }.orEmpty()
        val periode = buildString {
            if (metVan) append(" AND start_time >= ?")
            if (metTot) append(" AND start_time < ?")
        }

        return """
            SELECT trace_id, span_id, parent_span_id, status, name, start_time,
                   attributes->>'dpl.core.processing_activity_id' AS activiteit,
                   attributes->>'dpl.core.data_subject_id_type' AS subject_type,
                   attributes->>'dpl.core.data_subject_id' AS subject_id,
                   attributes->>'moza.ldv.uitkomst' AS uitkomst,
                   attributes->>'publicatie.bericht_id' AS bericht_id,
                   attributes->>'publicatie.doel' AS doel,
                   attributes->>'publicatie.poging' AS poging,
                   attributes->>'publicatie.fout' AS publicatie_fout,
                   attributes->>'exception.type' AS fout_type,
                   attributes->>'exception.message' AS fout_message
              FROM $tabel
             WHERE ((attributes->>'dpl.core.data_subject_id_type' = ?
                     AND attributes->>'dpl.core.data_subject_id' = ?)$viaBerichtId)$periode
        """.trimIndent()
    }

    private fun naarRij(r: ResultSet) = LogboekRij(
        traceId = r.getString("trace_id"),
        spanId = r.getString("span_id"),
        parentSpanId = r.getString("parent_span_id"),
        status = r.getString("status"),
        naam = r.getString("name"),
        start = Instant.ofEpochMilli(r.getLong("start_time")),
        activiteit = r.getString("activiteit"),
        subjectType = r.getString("subject_type"),
        subjectId = r.getString("subject_id"),
        uitkomstAttribuut = r.getString("uitkomst"),
        berichtId = r.getString("bericht_id"),
        doel = r.getString("doel"),
        poging = r.getString("poging")?.toLongOrNull(),
        publicatieFout = r.getString("publicatie_fout"),
        foutType = r.getString("fout_type"),
        foutMessage = r.getString("fout_message"),
    )
}
