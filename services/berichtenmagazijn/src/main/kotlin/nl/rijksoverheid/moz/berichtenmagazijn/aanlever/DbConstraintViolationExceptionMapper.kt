package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.exception.Problem
import nl.rijksoverheid.moz.fbs.common.exception.ProblemMediaType
import org.hibernate.exception.ConstraintViolationException
import org.jboss.logging.Logger
import java.net.URI
import java.util.UUID

/**
 * Mapt Hibernate `ConstraintViolationException` van DB-niveau.
 *
 * Alleen unique-key-violations (SQL state 23505) zijn client-conflicten (409).
 * NOT-NULL, CHECK, FK en andere integrity-violations duiden op programmeer- of
 * schemafouten en worden als 500 teruggegeven met een correlation-id voor support.
 */
@Provider
class DbConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {

    private val log = Logger.getLogger(DbConstraintViolationExceptionMapper::class.java)

    override fun toResponse(exception: ConstraintViolationException): Response {
        val sqlState = exception.sqlException?.sqlState

        return if (sqlState == UNIQUE_VIOLATION_SQL_STATE) {
            log.infof("Unique constraint geschonden: %s", exception.constraintName ?: "(onbekend)")
            val problem = Problem(
                title = "Conflict",
                status = 409,
                detail = "Aanlevering conflicteert met bestaande data.",
            )
            Response.status(409)
                .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
                .entity(problem)
                .build()
        } else {
            val errorId = UUID.randomUUID()
            log.errorf(
                exception,
                "DB-constraint geschonden (errorId=%s): state=%s constraint=%s",
                errorId,
                sqlState ?: "(onbekend)",
                exception.constraintName ?: "(onbekend)",
            )
            val problem = Problem(
                title = "Internal Server Error",
                status = 500,
                detail = "Er is een interne fout opgetreden. Vermeld errorId bij contact met support.",
                instance = URI.create("urn:uuid:$errorId"),
            )
            Response.status(500)
                .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
                .entity(problem)
                .build()
        }
    }

    private companion object {
        // SQLSTATE voor integrity constraint unique violation (ANSI SQL, H2/Postgres).
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
    }
}
