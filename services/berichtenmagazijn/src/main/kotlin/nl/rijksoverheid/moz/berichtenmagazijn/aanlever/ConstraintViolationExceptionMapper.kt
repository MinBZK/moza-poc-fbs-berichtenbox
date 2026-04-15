package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.Problem
import nl.rijksoverheid.moz.fbs.common.ProblemMediaType
import org.hibernate.exception.ConstraintViolationException
import org.jboss.logging.Logger

/**
 * Mapt Hibernate `ConstraintViolationException` (DB-level, bv. duplicate key of
 * violation van een `@Column(unique=true)`) naar 409 Conflict. Momenteel alleen de
 * primary key `berichtId` is uniek — een server-generated UUID collideert praktisch
 * nooit. Mapper staat klaar voor toekomstige unieke indices (bv. idempotency-key).
 */
@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {

    private val log = Logger.getLogger(ConstraintViolationExceptionMapper::class.java)

    override fun toResponse(exception: ConstraintViolationException): Response {
        log.warnf("DB-constraint geschonden: %s", exception.constraintName ?: exception.message)

        val problem = Problem(
            title = "Conflict",
            status = 409,
            detail = "Aanlevering conflicteert met bestaande data.",
        )

        return Response.status(409)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .build()
    }
}
