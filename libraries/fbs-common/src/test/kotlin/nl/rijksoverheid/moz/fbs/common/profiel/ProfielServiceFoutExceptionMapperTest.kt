package nl.rijksoverheid.moz.fbs.common.profiel

import nl.rijksoverheid.moz.fbs.common.exception.Problem
import nl.rijksoverheid.moz.fbs.common.exception.ProblemMediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class ProfielServiceFoutExceptionMapperTest {

    private val mapper = ProfielServiceFoutExceptionMapper()

    @Test
    fun `timeout-fault levert 503 met Retry-After 30`() {
        val response = mapper.toResponse(ProfielServiceFoutException.timeout())

        assertEquals(503, response.status)
        assertEquals("30", response.getHeaderString("Retry-After"))
    }

    @Test
    fun `mediatype is application problem+json`() {
        val response = mapper.toResponse(ProfielServiceFoutException.netwerk())

        assertEquals(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE, response.mediaType)
    }

    @Test
    fun `body is Problem met correcte velden`() {
        val response = mapper.toResponse(ProfielServiceFoutException.upstreamError(500))
        val problem = response.entity as Problem

        assertEquals(503, problem.status)
        assertEquals("Profiel-service tijdelijk niet beschikbaar", problem.title)
        assertEquals(URI.create("https://moza.nl/problems/profiel-service-onbereikbaar"), problem.type)
        assertNotNull(problem.detail)
        assertTrue(problem.detail!!.contains("30 seconden"), "detail moet retry-window vermelden: ${problem.detail}")
    }

    @Test
    fun `instance is urn uuid voor correlatie naar log`() {
        val response = mapper.toResponse(ProfielServiceFoutException.malformed())
        val problem = response.entity as Problem

        assertNotNull(problem.instance)
        assertTrue(
            problem.instance!!.toString().startsWith("urn:uuid:"),
            "instance moet urn:uuid:<id> zijn: ${problem.instance}",
        )
    }

    @Test
    fun `body bevat niet de oorspronkelijke exception-message (geen lek)`() {
        // upstreamError-message bevat statuscode; mag wel in log staan maar niet in Problem.detail.
        val response = mapper.toResponse(ProfielServiceFoutException.upstreamError(503))
        val problem = response.entity as Problem

        assertTrue(
            !problem.detail!!.contains("503") && !problem.detail!!.contains("HTTP"),
            "detail mag geen upstream-statuscode lekken: ${problem.detail}",
        )
    }

    @Test
    fun `onverwacht-fault levert ook 503 met Retry-After`() {
        // Catch-all-pad (NPE etc): client krijgt dezelfde 503 om interne details niet te lekken.
        val response = mapper.toResponse(ProfielServiceFoutException.onverwacht(IllegalStateException("interne bug")))

        assertEquals(503, response.status)
        assertEquals("30", response.getHeaderString("Retry-After"))
    }

    @Test
    fun `resolverMislukt-fault levert 503 met Retry-After`() {
        val response = mapper.toResponse(ProfielServiceFoutException.resolverMislukt(RuntimeException("await timeout")))

        assertEquals(503, response.status)
        assertEquals("30", response.getHeaderString("Retry-After"))
    }

    @Test
    fun `Problem-instance hergebruikt errorId uit de exception (geen nieuwe generatie)`() {
        // Mapper-randomUUID() zou cross-log-correlatie service ↔ client breken.
        val exception = ProfielServiceFoutException.timeout()
        val response = mapper.toResponse(exception)
        val problem = response.entity as Problem

        assertEquals(URI.create("urn:uuid:${exception.errorId}"), problem.instance)
    }
}
