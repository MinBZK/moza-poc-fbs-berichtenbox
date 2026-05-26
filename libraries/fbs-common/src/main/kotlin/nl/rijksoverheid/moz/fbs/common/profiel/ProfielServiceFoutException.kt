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
 */
class ProfielServiceFoutException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
