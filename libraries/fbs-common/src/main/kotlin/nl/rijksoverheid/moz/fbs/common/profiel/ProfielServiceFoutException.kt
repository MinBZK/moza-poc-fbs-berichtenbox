package nl.rijksoverheid.moz.fbs.common.profiel

/**
 * Bron-van-waarheid voor de Profiel-service-fout-taxonomie. Andere KDoc's
 * (MagazijnResolver, ProfielServiceFoutExceptionMapper) verwijzen hierheen.
 *
 * Gegooid wanneer de Profiel Service onbereikbaar is of een onleesbare respons
 * gaf na de fault-tolerance-retries. Wordt door [ProfielServiceFoutExceptionMapper]
 * vertaald naar HTTP 503 Problem JSON met `Retry-After`-header.
 *
 * **Fout-taxonomie** (per factory):
 *  - [timeout]: Mutiny-pipeline overschreed de inner-budget (geen item binnen tijd).
 *  - [upstreamError]: 5xx of niet-404 4xx van de Profiel-service.
 *  - [netwerk]: ProcessingException met IOException-cause (TCP reset, timeout op socket).
 *  - [malformed]: ProcessingException met JsonProcessingException-cause (parse-fout).
 *  - [onverwacht]: Catch-all voor onverwachte runtime-fouten in het resolve-pad.
 *  - [resolverMislukt]: Caller-side: resolver.await() faalde voordat de Mutiny-pipeline draaide.
 *
 * Geen onderscheid in caller-respons tussen de verschillende oorzaken; alles
 * leidt voor de caller tot "Profiel-Service tijdelijk niet beschikbaar". Voor
 * troubleshooting bewaart [cause] het oorspronkelijke type.
 *
 * Constructor is `private` om factory-bypass (vrije message-strings) te voorkomen —
 * alle messages zijn statisch zodat de mapper-log/detail geen call-site-injecties bevat.
 */
class ProfielServiceFoutException private constructor(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    companion object {
        fun timeout(cause: Throwable? = null) =
            ProfielServiceFoutException("Profiel-service overschreed timeout", cause)

        fun upstreamError(status: Int?, cause: Throwable? = null): ProfielServiceFoutException {
            val statusLabel = status?.toString() ?: "onbekend"
            return ProfielServiceFoutException("Profiel-service onbereikbaar (HTTP $statusLabel)", cause)
        }

        fun netwerk(cause: Throwable? = null) =
            ProfielServiceFoutException("Profiel-service onbereikbaar (netwerkfout)", cause)

        /** JSON-parse-fout op een upstream-respons. Cause is een [com.fasterxml.jackson.core.JsonProcessingException] (of subtype). */
        fun malformed(cause: Throwable? = null) =
            ProfielServiceFoutException("Profiel-service leverde onleesbare JSON-respons", cause)

        /**
         * Vangnet voor onverwachte runtime-fouten in het resolve-pad die geen netwerk-,
         * upstream- of parse-categorie hebben (bv. NullPointerException uit de gegenereerde
         * client, IllegalStateException in interne mapping). Apart van [malformed] zodat
         * de cause-categorie traceerbaar blijft in logs.
         */
        fun onverwacht(cause: Throwable? = null) =
            ProfielServiceFoutException("Profiel-service onverwachte interne fout", cause)

        fun resolverMislukt(cause: Throwable) =
            ProfielServiceFoutException("Resolver-aanroep mislukt", cause)
    }
}
