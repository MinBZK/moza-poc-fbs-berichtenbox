package nl.rijksoverheid.moz.fbs.common.profiel

/**
 * Gegooid wanneer de Profiel Service onbereikbaar is of een onleesbare respons
 * gaf na de fault-tolerance-retries (5xx, niet-404 4xx, ProcessingException,
 * timeout, malformed JSON). Wordt door [ProfielServiceFoutExceptionMapper]
 * vertaald naar HTTP 503 Problem JSON met `Retry-After`-header.
 *
 * Geen onderscheid in caller-respons tussen de verschillende oorzaken; alles
 * leidt voor de caller tot "Profiel-Service tijdelijk niet beschikbaar". Voor
 * troubleshooting bewaart [cause] het oorspronkelijke type.
 *
 * Gebruik de companion-factories voor consistente foutberichten over callsites heen.
 */
class ProfielServiceFoutException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    companion object {
        fun timeout(cause: Throwable? = null) =
            ProfielServiceFoutException("Profiel-service overschreed timeout", cause)

        fun upstreamError(status: Int, cause: Throwable? = null) =
            ProfielServiceFoutException("Profiel-service onbereikbaar (HTTP $status)", cause)

        fun netwerk(cause: Throwable? = null) =
            ProfielServiceFoutException("Profiel-service onbereikbaar (netwerkfout)", cause)

        fun malformed(cause: Throwable? = null) =
            ProfielServiceFoutException("Profiel-service onleesbaar antwoord (JSON-parse-fout)", cause)

        fun onleesbaar(cause: Throwable? = null) =
            ProfielServiceFoutException("Profiel-service onleesbaar antwoord", cause)

        fun resolverMislukt(cause: Throwable) =
            ProfielServiceFoutException("Resolver-aanroep mislukt", cause)
    }
}
