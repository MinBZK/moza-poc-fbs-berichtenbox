package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.NotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProblemExceptionMapperTest {

    private val mapper = ProblemExceptionMapper()

    @Test
    fun `behoudt 4xx detail zodat client weet wat er mis is`() {
        val response = mapper.toResponse(NotFoundException("bericht niet gevonden"))

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
    fun `saneert stacktrace-achtige 4xx detail`() {
        val response = mapper.toResponse(
            BadRequestException("Input fout at nl.rijksoverheid.moz.Foo.bar(Foo.kt:42) op regel 5"),
        )

        val problem = response.entity as Problem
        assertFalse(problem.detail!!.contains("Foo.kt:42"), "file+line moet weg: ${problem.detail}")
        assertFalse(problem.detail!!.contains("at nl.rijksoverheid"), "frame moet weg: ${problem.detail}")
    }

    @Test
    fun `maskeert 5xx detail en voegt correlation-id toe`() {
        val response = mapper.toResponse(InternalServerErrorException("SELECT * FROM users; stacktrace lek"))

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
    fun `geeft 4xx een correlation-id in instance voor support-traceability`() {
        val response = mapper.toResponse(BadRequestException("ongeldig"))

        val problem = response.entity as Problem
        // 4xx krijgt een errorId zodat support een concrete request kan terugvinden bij klacht.
        // Detail wordt niet gemaskeerd (zoals bij 5xx) — "ongeldig" blijft zichtbaar.
        assertEquals("ongeldig", problem.detail)
        assertNotNull(problem.instance)
    }
}
