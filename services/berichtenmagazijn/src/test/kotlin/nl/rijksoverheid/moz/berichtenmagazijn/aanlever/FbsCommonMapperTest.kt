package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.quarkus.test.junit.QuarkusTest
import org.hibernate.exception.ConstraintViolationException as HibernateConstraintViolationException
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.NotFoundException
import nl.rijksoverheid.moz.fbs.common.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.DomainValidationExceptionMapper
import nl.rijksoverheid.moz.fbs.common.IllegalArgumentExceptionMapper
import nl.rijksoverheid.moz.fbs.common.Problem
import nl.rijksoverheid.moz.fbs.common.ProblemExceptionMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
class FbsCommonMapperTest {

    @Test
    fun `IllegalArgumentExceptionMapper behandelt generieke IAE als 500 bug, lekt boodschap niet`() {
        val response = IllegalArgumentExceptionMapper()
            .toResponse(IllegalArgumentException("No enum constant com.internal.Secret.FOO"))

        assertEquals(500, response.status)
        assertEquals("application/problem+json", response.mediaType.toString())

        val problem = response.entity as Problem
        assertEquals("Internal Server Error", problem.title)
        assertEquals(500, problem.status)
        assertNotEquals("No enum constant com.internal.Secret.FOO", problem.detail)
        assertNotNull(problem.instance)
        assertTrue(problem.instance!!.toString().startsWith("urn:uuid:"))
    }

    @Test
    fun `DomainValidationExceptionMapper exposeert handgeschreven domeinboodschap`() {
        val response = DomainValidationExceptionMapper()
            .toResponse(DomainValidationException("onderwerp mag niet leeg zijn"))

        assertEquals(400, response.status)
        val problem = response.entity as Problem
        assertEquals("Bad Request", problem.title)
        assertEquals("onderwerp mag niet leeg zijn", problem.detail)
    }

    @Test
    fun `ProblemExceptionMapper behoudt 4xx detail zodat client weet wat er mis is`() {
        val response = ProblemExceptionMapper()
            .toResponse(NotFoundException("bericht niet gevonden"))

        assertEquals(404, response.status)
        val problem = response.entity as Problem
        assertEquals(404, problem.status)
        assertEquals("Not Found", problem.title)
        assertEquals("bericht niet gevonden", problem.detail)
        // 4xx krijgt een correlation-id voor support-traceability (zonder detail te maskeren).
        assertNotNull(problem.instance)
        assertTrue(problem.instance!!.toString().startsWith("urn:uuid:"))
    }

    @Test
    fun `ProblemExceptionMapper saneert stacktrace-achtige 4xx detail`() {
        val response = ProblemExceptionMapper()
            .toResponse(
                jakarta.ws.rs.BadRequestException(
                    "Input fout at nl.rijksoverheid.moz.Foo.bar(Foo.kt:42) op regel 5",
                ),
            )

        val problem = response.entity as Problem
        assertFalse(problem.detail!!.contains("Foo.kt:42"), "file+line moet weg: ${problem.detail}")
        assertFalse(problem.detail!!.contains("at nl.rijksoverheid"), "frame moet weg: ${problem.detail}")
    }

    @Test
    fun `ProblemExceptionMapper maskeert 5xx detail en voegt correlation-id toe`() {
        val response = ProblemExceptionMapper()
            .toResponse(InternalServerErrorException("SELECT * FROM users; stacktrace lek"))

        assertEquals(500, response.status)
        val problem = response.entity as Problem
        assertEquals(500, problem.status)
        assertNotEquals("SELECT * FROM users; stacktrace lek", problem.detail)
        assertNotNull(problem.detail)
        assertTrue(problem.detail!!.contains("errorId"))
        assertNotNull(problem.instance)
        assertTrue(problem.instance!!.toString().startsWith("urn:uuid:"))
    }

    @Test
    fun `ProblemExceptionMapper geeft 4xx een correlation-id in instance voor support-traceability`() {
        val response = ProblemExceptionMapper()
            .toResponse(BadRequestException("ongeldig"))

        val problem = response.entity as Problem
        // 4xx krijgt een errorId zodat support een concrete request kan terugvinden bij klacht.
        // Detail wordt niet gemaskeerd (zoals bij 5xx) — "ongeldig" blijft zichtbaar.
        assertEquals("ongeldig", problem.detail)
        assertNotNull(problem.instance)
    }

    @Test
    fun `Hibernate ConstraintViolationException met unique-violation SQL state mapt naar 409 Conflict`() {
        val sqlEx = java.sql.SQLException("unique violation", "23505")
        val hibernateEx = HibernateConstraintViolationException(
            "constraint violated", sqlEx, "uq_bericht_test",
        )

        val response = DbConstraintViolationExceptionMapper().toResponse(hibernateEx)

        assertEquals(409, response.status)
        assertEquals("application/problem+json", response.mediaType.toString())
        val problem = response.entity as Problem
        assertEquals("Conflict", problem.title)
        assertEquals(409, problem.status)
        assertNotNull(problem.detail)
    }

    @Test
    fun `Hibernate ConstraintViolationException zonder unique-violation state mapt naar 500 met errorId`() {
        // NOT-NULL / CHECK / FK violations zijn programmeer- of schemafouten, geen client-conflict.
        val sqlEx = java.sql.SQLException("null value in non-null column", "23502")
        val hibernateEx = HibernateConstraintViolationException(
            "not-null violation", sqlEx, "nn_bericht_afzender",
        )

        val response = DbConstraintViolationExceptionMapper().toResponse(hibernateEx)

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

        val problem = DbConstraintViolationExceptionMapper().toResponse(hibernateEx).entity as Problem

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

        val problem = DbConstraintViolationExceptionMapper().toResponse(hibernateEx).entity as Problem

        assertNotNull(problem.detail)
        assertFalse(problem.detail!!.contains("nn_bericht"), "constraintName mag niet in detail")
        assertFalse(problem.detail!!.contains("23502"), "SQL state mag niet in detail")
        assertFalse(problem.detail!!.contains("afzender"), "kolomnaam mag niet in detail")
        assertFalse(problem.detail!!.contains("null value"), "raw SQL message mag niet in detail")
        assertNotNull(problem.instance, "500 moet correlation-id bevatten voor support")
    }
}
