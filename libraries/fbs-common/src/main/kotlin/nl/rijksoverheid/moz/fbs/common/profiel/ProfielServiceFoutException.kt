package nl.rijksoverheid.moz.fbs.common.profiel

import java.util.UUID

/**
 * Gegooid wanneer de Profiel Service onbereikbaar is of een onleesbare respons gaf.
 * Wordt door [ProfielServiceFoutExceptionMapper] vertaald naar HTTP 503 Problem JSON
 * met `Retry-After`-header.
 *
 * Constructor is `private` om factory-bypass (vrije message-strings) te voorkomen —
 * alle messages zijn statisch zodat de mapper-log/detail geen call-site-injecties bevat.
 * [categorie] is verplicht zodat log-aggregatie (Loki/CloudWatch) per fault-mode kan
 * filteren zonder regex op message; [httpStatus] alleen gezet bij `UPSTREAM_ERROR`.
 *
 * [errorId] wordt bij constructie gegenereerd zodat de service-laag (cleanup-paden,
 * orchestratie-logs) dezelfde id kan loggen die de mapper later in `Problem.instance`
 * en de mapper-log neerzet. Daardoor kan support een client-side `urn:uuid:<errorId>`
 * direct correleren naar alle service-log-regels rond de fout (cleanup + root-cause),
 * niet alleen de mapper-regel.
 */
class ProfielServiceFoutException private constructor(
    val categorie: Categorie,
    val httpStatus: Int? = null,
    val errorId: UUID = UUID.randomUUID(),
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    enum class Categorie {
        TIMEOUT,
        UPSTREAM_ERROR,
        NETWERK,
        MALFORMED,
        ONVERWACHT,
        RESOLVER_MISLUKT,
        /**
         * Profiel-respons gelukt, maar geen enkele opt-in OIN matched een geconfigureerd
         * magazijn (volledige config-drift). Geen Profiel-storing; eigen-config-fout.
         * Caller handelt apart af (eigen foutmelding richting eindgebruiker, niet 503).
         */
        CONFIG_DRIFT,
    }

    companion object {
        fun timeout(cause: Throwable? = null) =
            ProfielServiceFoutException(Categorie.TIMEOUT, message = "Profiel-service overschreed timeout", cause = cause)

        fun upstreamError(status: Int?, cause: Throwable? = null): ProfielServiceFoutException {
            val statusLabel = status?.toString() ?: "onbekend"
            return ProfielServiceFoutException(
                Categorie.UPSTREAM_ERROR,
                httpStatus = status,
                message = "Profiel-service onbereikbaar (HTTP $statusLabel)",
                cause = cause,
            )
        }

        fun netwerk(cause: Throwable? = null) =
            ProfielServiceFoutException(Categorie.NETWERK, message = "Profiel-service onbereikbaar (netwerkfout)", cause = cause)

        /** JSON-parse-fout op een upstream-respons. Cause is een [com.fasterxml.jackson.core.JsonProcessingException] (of subtype). */
        fun malformed(cause: Throwable? = null) =
            ProfielServiceFoutException(Categorie.MALFORMED, message = "Profiel-service leverde onleesbare JSON-respons", cause = cause)

        /**
         * Vangnet voor onverwachte runtime-fouten in het resolve-pad die geen netwerk-,
         * upstream- of parse-categorie hebben (bv. NullPointerException uit de gegenereerde
         * client, IllegalStateException in interne mapping).
         */
        fun onverwacht(cause: Throwable? = null) =
            ProfielServiceFoutException(Categorie.ONVERWACHT, message = "Profiel-service onverwachte interne fout", cause = cause)

        fun resolverMislukt(cause: Throwable) =
            ProfielServiceFoutException(Categorie.RESOLVER_MISLUKT, message = "Resolver-aanroep mislukt", cause = cause)

        /**
         * Alle opted-in OINs onbekend bij magazijn-config; caller geeft eigen foutmelding.
         *
         * **Invariant**: geen dynamische `message` of `cause` toevoegen zonder PII-review
         * van [ProfielServiceFoutExceptionMapper] — die mapper logt voor CONFIG_DRIFT een
         * stacktrace; een cause met upstream-URL (BSN/RSIN in pad) zou daarmee lekken.
         */
        fun configDrift() =
            ProfielServiceFoutException(Categorie.CONFIG_DRIFT, message = "Configuratie-mismatch: opt-in OINs onbekend bij magazijn-config")
    }
}
