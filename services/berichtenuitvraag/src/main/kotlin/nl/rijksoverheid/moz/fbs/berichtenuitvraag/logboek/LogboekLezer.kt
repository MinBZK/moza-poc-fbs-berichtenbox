package nl.rijksoverheid.moz.fbs.berichtenuitvraag.logboek

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.logboek.LogboekLezing
import nl.rijksoverheid.moz.fbs.common.logboek.LogboekRij
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.sql.DriverManager
import java.time.Instant

/**
 * Leest het logboek van de uitvraag. Deze dienst heeft geen eigen datasource; het logboek staat
 * in de PostgreSQL waar de wrapper naartoe schrijft, dus de inzage leest met diezelfde gegevens.
 * Een verbinding per inzage: een beheerder vraagt zelden in.
 */
@ApplicationScoped
class LogboekLezer(
    @param:ConfigProperty(name = "logboekdataverwerking.postgresql.url") private val url: String,
    @param:ConfigProperty(name = "logboekdataverwerking.postgresql.username") private val gebruiker: String,
    @param:ConfigProperty(name = "logboekdataverwerking.postgresql.password") private val wachtwoord: String,
    @param:ConfigProperty(name = "logboekdataverwerking.postgresql.table") private val tabel: String,
) {

    fun lees(betrokkene: Identificatienummer, van: Instant?, tot: Instant?): List<LogboekRij> =
        DriverManager.getConnection(url, gebruiker, wachtwoord).use { connection ->
            LogboekLezing.lees(connection, tabel, betrokkene, van, tot)
        }
}
