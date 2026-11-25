package nl.rijksoverheid.moz.fbs.berichtenmagazijn.logboek

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

/**
 * Leest het logboek van één betrokkene uit de logboektabel. De wrapper kan alleen schrijven;
 * dit leest via de gewone datasource en gaat er dus van uit dat het logboek in dezelfde
 * database staat als de berichten (zo staat het ingericht, ook op Fundament).
 */
@ApplicationScoped
class LogboekLezer(
    private val dataSource: DataSource,
    @param:ConfigProperty(name = "logboekdataverwerking.postgresql.table") private val tabel: String,
) {

    init {
        // De tabelnaam komt uit configuratie en gaat letterlijk de SQL in.
        require(TABELNAAM.matches(tabel)) { "Ongeldige logboektabelnaam" }
    }

    /**
     * Rijen met de betrokkene als subject, plus de rijen die het logboek op het bericht-id
     * vastlegde (publicatie van een al verwijderd bericht) voor berichten van deze betrokkene.
     * Soft-deleted berichten tellen mee; na een hard-delete is die koppeling weg.
     */
    fun lees(betrokkene: Identificatienummer, van: Instant?, tot: Instant?): List<LogboekRij> {
        val periode = buildString {
            if (van != null) append(" AND start_time >= ?")
            if (tot != null) append(" AND start_time < ?")
        }
        val sql = """
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
                     AND attributes->>'dpl.core.data_subject_id' = ?)
                 OR (attributes->>'dpl.core.data_subject_id_type' = 'BERICHT_ID_ONLY'
                     AND attributes->>'dpl.core.data_subject_id' IN (
                         SELECT CAST(bericht_id AS text) FROM berichten
                          WHERE ontvanger_type = ? AND ontvanger_waarde = ?)))
                   $periode
        """.trimIndent()

        return try {
            voerUit(sql, betrokkene, van, tot)
        } catch (ex: SQLException) {
            // De wrapper maakt de tabel pas bij de eerste logregel aan.
            if (ex.sqlState == ONBEKENDE_TABEL) emptyList() else throw ex
        }
    }

    private fun voerUit(sql: String, betrokkene: Identificatienummer, van: Instant?, tot: Instant?): List<LogboekRij> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                var i = 0
                statement.setString(++i, betrokkene.type.name)
                statement.setString(++i, betrokkene.waarde)
                statement.setString(++i, betrokkene.type.name)
                statement.setString(++i, betrokkene.waarde)
                van?.let { statement.setLong(++i, it.toEpochMilli()) }
                tot?.let { statement.setLong(++i, it.toEpochMilli()) }

                statement.executeQuery().use { resultaat ->
                    buildList { while (resultaat.next()) add(naarRij(resultaat)) }
                }
            }
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

    private companion object {
        const val ONBEKENDE_TABEL = "42P01"
        val TABELNAAM = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$")
    }
}
