package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.quarkus.test.junit.QuarkusTest
import nl.rijksoverheid.moz.fbs.common.exception.Problem
import org.hibernate.exception.ConstraintViolationException as HibernateConstraintViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// @QuarkusTest is nodig zodat quarkus-jacoco deze mapper-class instrumenteert; zonder
// Quarkus-context wordt DbConstraintViolationExceptionMapper niet meegeteld in de
// coverage-rapportage en zakt de bundle-coverage onder de 90%-drempel.
@QuarkusTest
class DbConstraintViolationExceptionMapperTest {

    private val mapper = DbConstraintViolationExceptionMapper()

    @Test
    fun `unique-violation SQL state mapt naar 409 Conflict`() {
        val sqlEx = java.sql.SQLException("unique violation", "23505")
        val hibernateEx = HibernateConstraintViolationException(
            "constraint violated", sqlEx, "uq_bericht_test",
        )

        val response = mapper.toResponse(hibernateEx)

        assertEquals(409, response.status)
        assertEquals("application/problem+json", response.mediaType.toString())
        val problem = response.entity as Problem
        assertEquals("Conflict", problem.title)
        assertEquals(409, problem.status)
        assertNotNull(problem.detail)
    }

    @Test
    fun `niet-unique violation state mapt naar 500 met errorId`() {
        // NOT-NULL / CHECK / FK violations zijn programmeer- of schemafouten, geen client-conflict.
        val sqlEx = java.sql.SQLException("null value in non-null column", "23502")
        val hibernateEx = HibernateConstraintViolationException(
            "not-null violation", sqlEx, "nn_bericht_afzender",
        )

        val response = mapper.toResponse(hibernateEx)

        assertEquals(500, response.status)
        val problem = response.entity as Problem
        assertEquals("Internal Server Error", problem.title)
        assertEquals(500, problem.status)
        assertNotNull(problem.instance)
        assertTrue(problem.instance!!.toString().startsWith("urn:uuid:"))
    }

    @Test
    fun `409 Problem detail lekt geen interne constraint-naam, SQL state of raw exception-message`() {
        // Borg dat een toekomstige refactor niet per ongeluk schemanamen of DB-foutdetails
        // naar de client lekt. Detail moet generiek zijn, instance hoort er niet te zijn.
        val sqlEx = java.sql.SQLException(
            "duplicate key value violates unique constraint \"uq_bericht_idempotency\"",
            "23505",
        )
        val hibernateEx = HibernateConstraintViolationException(
            "constraint violated", sqlEx, "uq_bericht_idempotency",
        )

        val problem = mapper.toResponse(hibernateEx).entity as Problem

        assertNotNull(problem.detail)
        assertFalse(problem.detail!!.contains("uq_bericht"), "constraintName mag niet in detail")
        assertFalse(problem.detail!!.contains("23505"), "SQL state mag niet in detail")
        assertFalse(problem.detail!!.contains("duplicate key"), "raw SQL message mag niet in detail")
        assertNull(problem.instance, "409 hoort geen correlation-id te bevatten")
    }

    @Test
    fun `500 Problem detail lekt geen interne constraint-naam, SQL state of raw exception-message`() {
        // Borg dat een NOT-NULL/FK/CHECK violation een generieke 500-detail krijgt
        // zonder schemanamen of SQL-foutdetails. Correlation-id moet wél aanwezig zijn.
        val sqlEx = java.sql.SQLException(
            "null value in column \"afzender\" violates not-null constraint",
            "23502",
        )
        val hibernateEx = HibernateConstraintViolationException(
            "not-null violation", sqlEx, "nn_bericht_afzender",
        )

        val problem = mapper.toResponse(hibernateEx).entity as Problem

        assertNotNull(problem.detail)
        assertFalse(problem.detail!!.contains("nn_bericht"), "constraintName mag niet in detail")
        assertFalse(problem.detail!!.contains("23502"), "SQL state mag niet in detail")
        assertFalse(problem.detail!!.contains("afzender"), "kolomnaam mag niet in detail")
        assertFalse(problem.detail!!.contains("null value"), "raw SQL message mag niet in detail")
        assertNotNull(problem.instance, "500 moet correlation-id bevatten voor support")
    }
}
