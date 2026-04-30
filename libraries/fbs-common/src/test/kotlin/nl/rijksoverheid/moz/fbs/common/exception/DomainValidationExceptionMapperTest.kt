package nl.rijksoverheid.moz.fbs.common.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DomainValidationExceptionMapperTest {

    private val mapper = DomainValidationExceptionMapper()

    @Test
    fun `exposeert handgeschreven domeinboodschap als 400 Bad Request detail`() {
        val response = mapper.toResponse(DomainValidationException("onderwerp mag niet leeg zijn"))

        assertEquals(400, response.status)
        val problem = response.entity as Problem
        assertEquals("Bad Request", problem.title)
        assertEquals("onderwerp mag niet leeg zijn", problem.detail)
    }
}
