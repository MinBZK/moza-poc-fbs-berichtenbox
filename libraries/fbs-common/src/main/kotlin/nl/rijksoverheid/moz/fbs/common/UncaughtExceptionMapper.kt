package nl.rijksoverheid.moz.fbs.common

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import java.net.URI
import java.util.UUID

/**
 * Vangnet voor alle exceptions waarvoor geen specifiekere mapper bestaat (bv. fouten
 * uit interceptors of side-channel libraries zoals een falende observability-export
 * naar ClickHouse). Zonder dit mapper-type rendert RESTEasy z'n default error page
 * inclusief stacktrace in de response — een infolek.
 *
 * JAX-RS kiest mappers op type-specificiteit, dus deze mapper wordt alleen gebruikt
 * als geen [WebApplicationException]-, [IllegalArgumentException]-,
 * [com.fasterxml.jackson.core.JsonProcessingException]- of vergelijkbare mapper
 * matcht. De `@Priority` op `USER + 100` is een extra tiebreaker (hogere waarde =
 * lagere prioriteit) voor het onwaarschijnlijke geval dat ooit een andere mapper
 * hetzelfde generieke type zou claimen.
 *
 * Logt met correlation-id (zelfde patroon als [ProblemExceptionMapper] en
 * [IllegalArgumentExceptionMapper]) zodat support de oorzaak kan terugvinden zonder
 * dat interne details aan de client lekken.
 */
@Provider
@Priority(Priorities.USER + 100)
class UncaughtExceptionMapper : ExceptionMapper<Exception> {

    private val log = Logger.getLogger(UncaughtExceptionMapper::class.java)

    override fun toResponse(exception: Exception): Response {
        val errorId = UUID.randomUUID()
        log.errorf(
            exception,
            "Onverwachte exception (errorId=%s, type=%s): %s",
            errorId,
            exception.javaClass.name,
            exception.message,
        )

        val problem = Problem(
            title = "Internal Server Error",
            status = 500,
            detail = "Er is een interne fout opgetreden. Vermeld errorId bij contact met support.",
            instance = URI.create("urn:uuid:$errorId"),
        )

        return Response.status(500)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .build()
    }
}
