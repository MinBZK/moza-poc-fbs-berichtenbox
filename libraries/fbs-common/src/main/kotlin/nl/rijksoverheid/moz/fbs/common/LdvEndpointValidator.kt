package nl.rijksoverheid.moz.fbs.common

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * Borgt dat de verbinding naar het LDV (Logboek Dataverwerkingen) in productie-achtige
 * profielen versleuteld is. Persoonsgegevens (zoals dataSubjectId met BSN) mogen niet
 * onversleuteld over het netwerk — BIO 13.2.1 / AVG art. 32. In `dev` en `test` mag
 * plaintext voor lokale containers.
 *
 * De vorm van de check hangt af van de gekozen backend: een ClickHouse-endpoint is een
 * URL met een scheme, een PostgreSQL-verbinding een JDBC-URL waarin de versleuteling
 * uit de parameters blijkt. Beide keys worden onvoorwaardelijk geïnjecteerd, maar als
 * `Optional`: de ongebruikte backend hoeft geen waarde te hebben en mag de start niet
 * blokkeren.
 */
@ApplicationScoped
class LdvEndpointValidator(
    @param:ConfigProperty(name = DBMS_KEY, defaultValue = "clickhouse") private val dbms: String,
    // Optional, niet String met lege default: SmallRye behandelt een lege waarde als
    // afwezig en laat een gewone String-injectie dan falen met SRCFG00014 — precies wat
    // er gebeurt bij `${LDV_CLICKHOUSE_ENDPOINT:}` zodra die env-var weg is op een
    // PostgreSQL-deployment. De ongebruikte backend mag de start niet blokkeren.
    @param:ConfigProperty(name = CLICKHOUSE_KEY) private val clickhouseEndpoint: Optional<String>,
    @param:ConfigProperty(name = POSTGRESQL_KEY) private val postgresqlUrl: Optional<String>,
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
    // BEWUST ONVEILIG: zet de TLS-eis op het LDV-endpoint uit; rationale + voorwaarden in
    // de KDoc van OutboundTlsValidator.requireHttps. Default false (fail-closed).
    @param:ConfigProperty(name = UNSAFE_PLAINTEXT_KEY, defaultValue = "false")
    private val unsafeAllowPlaintext: Boolean,
) {

    fun onStartup(@Observes event: StartupEvent) {
        validate(
            profile,
            dbms,
            clickhouseEndpoint.orElse(""),
            postgresqlUrl.orElse(""),
            unsafeAllowPlaintext,
        )
    }

    /**
     * Backend-specifieke helft van de check: welke config-key het endpoint levert én in
     * welke vorm dat endpoint versleuteling garandeert. Beide zitten in dezelfde
     * enum-constante zodat er maar één beslissing over de backend te nemen valt — een
     * losse keuze per aspect kan uiteenlopen, waardoor een gekozen backend met de
     * waarde van de ándere gecontroleerd wordt.
     */
    private enum class Backend {
        CLICKHOUSE {
            override fun endpointUit(clickhouseEndpoint: String, postgresqlUrl: String) = clickhouseEndpoint

            override fun valideer(profile: String, endpoint: String, unsafeAllowPlaintext: Boolean) =
                OutboundTlsValidator.requireHttps(profile, endpoint, CLICKHOUSE_KEY, unsafeAllowPlaintext)
        },
        POSTGRESQL {
            override fun endpointUit(clickhouseEndpoint: String, postgresqlUrl: String) = postgresqlUrl

            override fun valideer(profile: String, endpoint: String, unsafeAllowPlaintext: Boolean) =
                OutboundTlsValidator.requireJdbcTls(profile, endpoint, POSTGRESQL_KEY, unsafeAllowPlaintext)
        };

        abstract fun endpointUit(clickhouseEndpoint: String, postgresqlUrl: String): String

        abstract fun valideer(profile: String, endpoint: String, unsafeAllowPlaintext: Boolean)

        companion object {
            /** Aliassen en hoofdlettervormen volgen die van de wrapper (`ConfigurationLoader`). */
            fun van(dbms: String): Backend = when (dbms.lowercase()) {
                "clickhouse" -> CLICKHOUSE
                "postgresql", "postgres" -> POSTGRESQL
                else -> throw IllegalArgumentException(
                    "$DBMS_KEY heeft een onbekende waarde '$dbms'; geldig zijn 'clickhouse' en 'postgresql'",
                )
            }
        }
    }

    companion object {
        const val DBMS_KEY = "logboekdataverwerking.dbms"
        const val CLICKHOUSE_KEY = "logboekdataverwerking.clickhouse.endpoint"
        const val POSTGRESQL_KEY = "logboekdataverwerking.postgresql.url"
        const val UNSAFE_PLAINTEXT_KEY = "fbs.ldv.unsafe-allow-plaintext-endpoint"

        /**
         * Beide endpoint-waarden gaan mee: de ongebruikte backend heeft een lege default en
         * mag de uitkomst niet beïnvloeden. Zo controleert deze functie precies wat
         * `onStartup` doet, inclusief de keuze wélk endpoint beoordeeld wordt.
         */
        fun validate(
            profile: String,
            dbms: String,
            clickhouseEndpoint: String = "",
            postgresqlUrl: String = "",
            unsafeAllowPlaintext: Boolean = false,
        ) {
            val backend = Backend.van(dbms)

            backend.valideer(
                profile,
                backend.endpointUit(clickhouseEndpoint, postgresqlUrl),
                unsafeAllowPlaintext,
            )
        }
    }
}
