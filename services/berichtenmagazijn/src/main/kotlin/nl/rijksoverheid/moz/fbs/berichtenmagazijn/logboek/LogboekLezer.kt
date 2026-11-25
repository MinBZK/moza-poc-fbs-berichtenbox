package nl.rijksoverheid.moz.fbs.berichtenmagazijn.logboek

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.logboek.LogboekLezing
import nl.rijksoverheid.moz.fbs.common.logboek.LogboekRij
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import javax.sql.DataSource

/**
 * Leest het logboek van het magazijn via de gewone datasource: het logboek staat in dezelfde
 * database als de berichten (zo staat het ingericht, ook op Fundament).
 */
@ApplicationScoped
class LogboekLezer(
    private val dataSource: DataSource,
    @param:ConfigProperty(name = "logboekdataverwerking.postgresql.table") private val tabel: String,
) {

    /**
     * Soft-deleted berichten tellen mee voor de regels op bericht-id; na een hard-delete is die
     * koppeling weg.
     */
    fun lees(betrokkene: Identificatienummer, van: Instant?, tot: Instant?): List<LogboekRij> =
        dataSource.connection.use { connection ->
            LogboekLezing.lees(connection, tabel, betrokkene, van, tot, BERICHT_IDS_VAN_BETROKKENE)
        }

    private companion object {
        const val BERICHT_IDS_VAN_BETROKKENE =
            "SELECT CAST(bericht_id AS text) FROM berichten WHERE ontvanger_type = ? AND ontvanger_waarde = ?"
    }
}
