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
 * Vangnet voor generieke [IllegalArgumentException]s uit dependencies/JDK/intern gebruik
 * van `require(...)`. Zonder deze mapper wordt het een 500 zonder Problem-shape en lekken
 * stacktraces naar de client.
 *
 * Generieke IAEs duiden op programmeerfouten, niet op client-input — daarom 500 met
 * correlation-id in plaats van 400. Voor client-exposed domein-validatie: gebruik
 * [DomainValidationException] + [DomainValidationExceptionMapper].
 *
 * `@Priority` ligt expliciet onder die van [DomainValidationExceptionMapper]
 * (`USER - 100`); JAX-RS prefereert lágere numerieke waarden, dus de specifiekere
 * mapper wint zonder afhankelijkheid van scan-volgorde.
 */
@Provider
@Priority(Priorities.USER)
class IllegalArgumentExceptionMapper : ExceptionMapper<IllegalArgumentException> {

    private val log = Logger.getLogger(IllegalArgumentExceptionMapper::class.java)

    override fun toResponse(exception: IllegalArgumentException): Response {
        val errorId = UUID.randomUUID()
        log.errorf(exception, "Onverwachte IllegalArgumentException (errorId=%s): %s", errorId, exception.message)

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
