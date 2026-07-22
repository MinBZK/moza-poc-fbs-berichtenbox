package nl.rijksoverheid.moz.fbs.democonsole.legen

import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import jakarta.enterprise.context.ApplicationScoped
import javax.sql.DataSource as JavaxDataSource

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

            bron.connection.use { verbinding ->
                verbinding.createStatement().use { stmt ->
                    stmt.execute(
                        "TRUNCATE berichten, bijlagen, bericht_status, publicatie_deliveries " +
                            "RESTART IDENTITY CASCADE",
                    )
                }
            }

            aantal
        }

    fun aantallen(): Map<String, Int> = bronnen.mapValues { (_, bron) -> telBerichten(bron) }

    private fun telBerichten(bron: JavaxDataSource): Int =
        bron.connection.use { verbinding ->
            verbinding.createStatement().use { stmt ->
                stmt.executeQuery("SELECT count(*) FROM berichten").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
}
