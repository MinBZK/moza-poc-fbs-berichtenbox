package nl.rijksoverheid.moz.fbs.common

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Borgt dat de verbinding naar het LDV (Logboek Dataverwerkingen) in productie-achtige
 * profielen versleuteld is. Persoonsgegevens (zoals dataSubjectId met BSN) mogen niet
 * onversleuteld over het netwerk — BIO 13.2.1 / AVG art. 32. In `dev` en `test` mag
 * plaintext voor lokale containers.
 *
 * De vorm van de check hangt af van de gekozen backend: een ClickHouse-endpoint is een
 * URL met een scheme, een PostgreSQL-verbinding een JDBC-URL waarin de versleuteling
 * uit de parameters blijkt. Beide keys worden geïnjecteerd met een lege default, zodat
 * de ongebruikte backend geen env-var hoeft te hebben.
 */
@ApplicationScoped
class LdvEndpointValidator(
    @param:ConfigProperty(name = DBMS_KEY, defaultValue = "clickhouse") private val dbms: String,
    @param:ConfigProperty(name = CLICKHOUSE_KEY, defaultValue = "") private val clickhouseEndpoint: String,
    @param:ConfigProperty(name = POSTGRESQL_KEY, defaultValue = "") private val postgresqlUrl: String,
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
    // BEWUST ONVEILIG: zet de TLS-eis op het LDV-endpoint uit; rationale + voorwaarden in
    // de KDoc van OutboundTlsValidator.requireHttps. Default false (fail-closed).
    @param:ConfigProperty(name = UNSAFE_PLAINTEXT_KEY, defaultValue = "false")
    private val unsafeAllowPlaintext: Boolean,
) {

    fun onStartup(@Observes event: StartupEvent) {
        val endpoint = if (dbms.lowercase() == "clickhouse") clickhouseEndpoint else postgresqlUrl

        validate(profile, dbms, endpoint, unsafeAllowPlaintext)
    }

    companion object {
        const val DBMS_KEY = "logboekdataverwerking.dbms"
        const val CLICKHOUSE_KEY = "logboekdataverwerking.clickhouse.endpoint"
        const val POSTGRESQL_KEY = "logboekdataverwerking.postgresql.url"
        const val UNSAFE_PLAINTEXT_KEY = "fbs.ldv.unsafe-allow-plaintext-endpoint"

        fun validate(
            profile: String,
            dbms: String,
            endpoint: String,
            unsafeAllowPlaintext: Boolean = false,
        ) {
            when (dbms.lowercase()) {
                "clickhouse" ->
                    OutboundTlsValidator.requireHttps(profile, endpoint, CLICKHOUSE_KEY, unsafeAllowPlaintext)

                "postgresql", "postgres" ->
                    OutboundTlsValidator.requireJdbcTls(profile, endpoint, POSTGRESQL_KEY, unsafeAllowPlaintext)

                else -> throw IllegalArgumentException(
                    "$DBMS_KEY heeft een onbekende waarde '$dbms'; geldig zijn 'clickhouse' en 'postgresql'",
                )
            }
        }
    }
}
