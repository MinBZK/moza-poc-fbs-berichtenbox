package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Problem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * De twee mappers die ervoor zorgen dat er nooit iets anders dan `problem+json` naar buiten komt.
 *
 * Ze grijpen op elkaar in: [UncaughtExceptionMapper] staat op `Exception` en zou zonder de
 * specifiekere [ProblemExceptionMapper] óók elke bewuste 404 opvangen en als 500 doorgeven. Dat is
 * geen theoretisch geval — het is de standaard-mapperkeuze van JAX-RS — en het is onzichtbaar tot
 * een client een 500 krijgt waar een 404 hoorde te staan.
 */
class FoutMappersTest {

    @Test
    fun `een zelfgebouwde problem-response gaat ongewijzigd door`() {
        val response = problemResponse(status = 404, title = "Not Found", detail = "Geen bericht")

        val uitkomst = ProblemExceptionMapper().toResponse(WebApplicationException(response))

        assertSame(response, uitkomst)
    }

    @Test
    fun `een exception van elders krijgt alsnog een problem-body met dezelfde status`() {
        val uitkomst = ProblemExceptionMapper().toResponse(NotFoundException("interne details"))

        assertEquals(404, uitkomst.status)
        assertEquals(PROBLEM_JSON, uitkomst.mediaType.toString())

        val problem = uitkomst.entity as Problem

        assertEquals("Not Found", problem.title)
        assertEquals(404, problem.status)
        // De melding van zo'n exception kan interne details dragen en hoort de client niet te halen;
        // een `detail` dat alleen de titel herhaalt voegt niets toe, dus die blijft weg.
        assertNull(problem.detail)
    }

    @Test
    fun `een onbekende status levert een neutrale titel op in plaats van een lege`() {
        val exception = WebApplicationException(Response.status(499).build())

        val problem = ProblemExceptionMapper().toResponse(exception).entity as Problem

        assertEquals("Error", problem.title)
        assertEquals(499, problem.status)
    }

    @Test
    fun `een onverwachte fout wordt een 500 met een foutId en zonder de melding zelf`() {
        val uitkomst = UncaughtExceptionMapper().toResponse(IllegalStateException("select * from berichten"))

        assertEquals(500, uitkomst.status)
        assertEquals(PROBLEM_JSON, uitkomst.mediaType.toString())

        val problem = uitkomst.entity as Problem

        assertEquals("Internal Server Error", problem.title)
        assertFalse(problem.detail.contains("select * from berichten"))
        assertTrue(problem.detail.contains("foutId"), "verwacht een correlatie-id, was: ${problem.detail}")
    }

    private companion object {
        const val PROBLEM_JSON = "application/problem+json"
    }
}
