package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Vangnet voor alles waar geen specifiekere mapper voor bestaat: 500 `problem+json` met een
 * correlatie-id, zodat een onverwachte fout niet als standaard-foutpagina naar buiten komt en toch
 * in de log terug te vinden is.
 *
 * De melding van de exception blijft uit het antwoord — die kan interne details dragen. De
 * `@Priority` is een extra tiebreaker voor het onwaarschijnlijke geval dat ooit een andere mapper
 * hetzelfde generieke type claimt; hogere waarde is lagere prioriteit.
 */
@Provider
@Priority(Priorities.USER + 100)
class UncaughtExceptionMapper : ExceptionMapper<Exception> {

    private val log = Logger.getLogger(UncaughtExceptionMapper::class.java)

    override fun toResponse(exception: Exception): Response {
        val foutId = UUID.randomUUID()

        log.errorf(exception, "Onverwachte fout (foutId=%s, type=%s)", foutId, exception.javaClass.name)

        return problemResponse(
            status = Response.Status.INTERNAL_SERVER_ERROR.statusCode,
            title = "Internal Server Error",
            detail = "Er is een onverwachte interne fout opgetreden. Vermeld foutId $foutId bij contact met support.",
        )
    }
}
