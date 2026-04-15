package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.quarkus.test.junit.QuarkusTest
import org.hibernate.exception.ConstraintViolationException as HibernateConstraintViolationException
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.NotFoundException
import nl.rijksoverheid.moz.fbs.common.IllegalArgumentExceptionMapper
import nl.rijksoverheid.moz.fbs.common.Problem
import nl.rijksoverheid.moz.fbs.common.ProblemExceptionMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
class FbsCommonMapperTest {

    @Test
    fun `IllegalArgumentException wordt gemapt naar 400 Problem met originele boodschap`() {
        val response = IllegalArgumentExceptionMapper()
            .toResponse(IllegalArgumentException("onderwerp mag niet leeg zijn"))

        assertEquals(400, response.status)
        assertEquals("application/problem+json", response.mediaType.toString())

        val problem = response.entity as Problem
        assertEquals("Bad Request", problem.title)
        assertEquals(400, problem.status)
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
        assertNull(problem.instance)
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
    fun `ProblemExceptionMapper logt 4xx niet als 5xx (geen correlation-id)`() {
        val response = ProblemExceptionMapper()
            .toResponse(BadRequestException("ongeldig"))

        val problem = response.entity as Problem
        assertNull(problem.instance)
    }

    @Test
    fun `Hibernate ConstraintViolationException wordt gemapt naar 409 Conflict`() {
        val sqlEx = java.sql.SQLException("unique violation")
        val hibernateEx = HibernateConstraintViolationException(
            "constraint violated", sqlEx, "uq_bericht_test",
        )

        val response = ConstraintViolationExceptionMapper().toResponse(hibernateEx)

        assertEquals(409, response.status)
        assertEquals("application/problem+json", response.mediaType.toString())
        val problem = response.entity as Problem
        assertEquals("Conflict", problem.title)
        assertEquals(409, problem.status)
        assertNotNull(problem.detail)
    }
}
