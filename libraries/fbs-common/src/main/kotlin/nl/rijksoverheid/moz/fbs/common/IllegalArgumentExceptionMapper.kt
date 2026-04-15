package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

/**
 * Domein-invarianten uit `require(...)` in init-blocks gooien [IllegalArgumentException].
 * Zonder deze mapper vallen die door naar 500; inhoudelijk zijn het echter 400-fouten.
 */
@Provider
class IllegalArgumentExceptionMapper : ExceptionMapper<IllegalArgumentException> {

    private val log = Logger.getLogger(IllegalArgumentExceptionMapper::class.java)

    override fun toResponse(exception: IllegalArgumentException): Response {
        log.infof("Ongeldige invoer: %s", exception.message)

        val problem = Problem(
            title = "Bad Request",
            status = 400,
            detail = exception.message,
        )

        return Response.status(400)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .build()
    }
}
