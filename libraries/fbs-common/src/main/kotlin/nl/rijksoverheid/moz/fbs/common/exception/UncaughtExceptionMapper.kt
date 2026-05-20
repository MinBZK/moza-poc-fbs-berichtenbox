package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Vangnet voor alle exceptions waarvoor geen specifiekere mapper bestaat. Voorkomt
 * info-lekken via standaard servlet-error-pagina's.
 *
 * JAX-RS kiest mappers op type-specificiteit. De `@Priority` op `USER + 100` is een extra tiebreaker (hogere waarde =
 * lagere prioriteit) voor het onwaarschijnlijke geval dat ooit een andere mapper hetzelfde generieke type zou claimen.
 *
 * Logt met correlation-id zodat support de oorzaak kan terugvinden.
 */
@Provider
@Priority(Priorities.USER + 100)
class UncaughtExceptionMapper : ExceptionMapper<Exception> {

    private val log = Logger.getLogger(UncaughtExceptionMapper::class.java)

    override fun toResponse(exception: Exception): Response {
        val errorId = UUID.randomUUID()
        // Als ProblemExceptionMapper: `exception.message` blijft uit de log (saneer dekt
        // geen niet-numerieke PII); het exception-object geeft de stack mee via errorId.
        log.errorf(
            exception,
            "Onverwachte exception (errorId=%s, type=%s, cause=%s)",
            errorId,
            exception.javaClass.name,
            exception.cause?.javaClass?.simpleName ?: "geen",
        )
        return maskedServerErrorProblem(
            errorId = errorId,
            detail = "Er is een onverwachte interne fout opgetreden. Vermeld errorId bij contact met support.",
        )
    }
}
