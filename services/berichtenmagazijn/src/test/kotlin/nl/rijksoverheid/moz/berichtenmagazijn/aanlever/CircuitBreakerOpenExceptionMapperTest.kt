package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.quarkus.test.junit.QuarkusTest
import nl.rijksoverheid.moz.fbs.common.exception.Problem
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
class CircuitBreakerOpenExceptionMapperTest {

    private val mapper = CircuitBreakerOpenExceptionMapper()

    @Test
    fun `retourneert 503 Problem JSON wanneer circuit open is`() {
        val response = mapper.toResponse(CircuitBreakerOpenException("open"))

        assertEquals(503, response.status)
        assertEquals("application/problem+json", response.mediaType.toString())

        val problem = response.entity as Problem
        assertEquals("Service Unavailable", problem.title)
        assertEquals(503, problem.status)
        assertNotNull(problem.detail)
        assertTrue(problem.detail!!.contains("tijdelijk niet beschikbaar"))
    }
}
