package nl.rijksoverheid.moz.fbs.democonsole.legen

import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import jakarta.enterprise.context.ApplicationScoped
import java.sql.ResultSet
import javax.sql.DataSource as JavaxDataSource

/**
 * De SQL van het legen, apart zodat de invarianten toetsbaar zijn zonder database: alle statements
 * zijn ongekwalificeerd, want `currentSchema` van de datasource bepaalt in welk magazijn-schema ze
 * landen. Lokaal zijn dat twee databases met schema `public`, op ZAD één database met twee schema's.
 */
internal object LegenSql {

    const val DOMEIN = "TRUNCATE berichten, bijlagen, bericht_status, publicatie_deliveries RESTART IDENTITY CASCADE"

    // De LDV-wrapper maakt zijn tabel lui aan met CREATE TABLE IF NOT EXISTS, dus vóór het eerste
    // export-moment bestaat hij niet en zou een kale TRUNCATE het hele legen laten falen.
    const val LOGBOEK_BESTAAT =
        "SELECT count(*) FROM pg_tables WHERE schemaname = current_schema() AND tablename = 'logboek_dataverwerkingen'"

    const val LOGBOEK = "TRUNCATE logboek_dataverwerkingen RESTART IDENTITY"
}

/**
 * Directe DB-toegang op de magazijn-databases voor het legen. Bewust "vieze" kennis van
 * het magazijn-schema in de wegwerp-console i.p.v. een reset-endpoint in productiecode.
 * TRUNCATE ... RESTART IDENTITY CASCADE geeft een schone lei inclusief child-tabellen.
 */
@ApplicationScoped
class MagazijnDatabase(
    @param:DataSource("magazijn-a-db") private val magazijnA: AgroalDataSource,
    @param:DataSource("magazijn-b-db") private val magazijnB: AgroalDataSource,
) {

    private val bronnen: Map<String, JavaxDataSource> = mapOf(
        "magazijn-a" to magazijnA,
        "magazijn-b" to magazijnB,
    )

    fun leegAlles(): Map<String, Int> =
        bronnen.mapValues { (_, bron) ->
            val aantal = telBerichten(bron)

            voerUit(bron, LegenSql.DOMEIN)
            leegLogboek(bron)

            aantal
        }

    fun aantallen(): Map<String, Int> = bronnen.mapValues { (_, bron) -> telBerichten(bron) }

    // Het logboek staat in %prod ín het magazijn-schema. Blijft het staan, dan toont het LDV na een
    // herstel nog de verwerkingen van de vorige demo terwijl de berichten weg zijn — en juist dat
    // logboek is wat we in een demo laten zien.
    private fun leegLogboek(bron: JavaxDataSource) {
        if (queryEnkeleInt(bron, LegenSql.LOGBOEK_BESTAAT) > 0) {
            voerUit(bron, LegenSql.LOGBOEK)
        }
    }

    private fun telBerichten(bron: JavaxDataSource): Int =
        queryEnkeleInt(bron, "SELECT count(*) FROM berichten")

    private fun voerUit(bron: JavaxDataSource, sql: String) {
        bron.connection.use { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute(sql)
            }
        }
    }

    private fun queryEnkeleInt(bron: JavaxDataSource, sql: String): Int =
        bron.connection.use { connection ->
            connection.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs -> eersteInt(rs) }
            }
        }

    private fun eersteInt(rs: ResultSet): Int = if (rs.next()) rs.getInt(1) else 0
}
