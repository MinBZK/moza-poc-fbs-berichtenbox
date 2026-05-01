package nl.rijksoverheid.moz.fbs.common.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class ProblemResponsesTest {

    @Test
    fun `problemResponse zonder instance zet status, content-type en Problem-velden`() {
        val response = problemResponse(status = 400, title = "Bad Request", detail = "iets ongeldig")

        assertEquals(400, response.status)
        assertEquals("application/problem+json", response.mediaType.toString())
        val problem = response.entity as Problem
        assertEquals("Bad Request", problem.title)
        assertEquals(400, problem.status)
        assertEquals("iets ongeldig", problem.detail)
        assertNull(problem.instance)
    }

    @Test
    fun `maskedServerErrorProblem met defaults - 500, masked detail, urn-uuid instance`() {
        val errorId = UUID.fromString("12345678-1234-5678-1234-567812345678")

        val problem = (maskedServerErrorProblem(errorId).entity as Problem)

        assertEquals(500, problem.status)
        assertEquals("Internal Server Error", problem.title)
        // Default detail mag de wording 'onverwachte interne fout' bevatten — dat is
        // het contract dat clients en support op leunen.
        assertEquals(
            "Er is een onverwachte interne fout opgetreden. Vermeld errorId bij contact met support.",
            problem.detail,
        )
        assertEquals("urn:uuid:$errorId", problem.instance.toString())
    }

    @Test
    fun `maskedServerErrorProblem honoreert overrides voor status, title en detail`() {
        val errorId = UUID.randomUUID()

        val problem = maskedServerErrorProblem(
            errorId = errorId,
            status = 503,
            title = "Service Unavailable",
            detail = "Backend onbereikbaar; probeer later opnieuw.",
        ).entity as Problem

        assertEquals(503, problem.status)
        assertEquals("Service Unavailable", problem.title)
        assertEquals("Backend onbereikbaar; probeer later opnieuw.", problem.detail)
        assertEquals("urn:uuid:$errorId", problem.instance.toString())
    }
}
