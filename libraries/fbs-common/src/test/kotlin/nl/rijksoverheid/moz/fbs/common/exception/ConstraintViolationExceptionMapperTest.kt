package nl.rijksoverheid.moz.fbs.common.exception

import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConstraintViolationExceptionMapperTest {

    private val mapper = ConstraintViolationExceptionMapper()

    @Test
    fun `single violation geeft 400 Problem met paramName en message`() {
        val exception = ConstraintViolationException(setOf(violation("onderwerp", "mag niet leeg zijn")))

        val response = mapper.toResponse(exception)
        val problem = response.entity as Problem

        assertEquals(400, response.status)
        assertEquals("Bad Request", problem.title)
        assertEquals("onderwerp: mag niet leeg zijn", problem.detail)
    }

    @Test
    fun `meerdere violations worden met puntkomma gejoined`() {
        val exception = ConstraintViolationException(
            setOf(
                violation("afzender", "formaat onjuist"),
                violation("inhoud", "te lang"),
            ),
        )

        val response = mapper.toResponse(exception)
        val detail = (response.entity as Problem).detail ?: ""

        assertTrue(detail.contains("afzender: formaat onjuist"), "afzender detail ontbreekt: $detail")
        assertTrue(detail.contains("inhoud: te lang"), "inhoud detail ontbreekt: $detail")
        assertTrue(detail.contains("; "), "scheidingsteken `; ` ontbreekt: $detail")
    }

    @Test
    fun `violation zonder propertyPath valt terug op toString`() {
        val mock = mockk<ConstraintViolation<Any>>()
        val mockPath = mockk<Path>()
        every { mockPath.toString() } returns ""
        every { mockPath.iterator() } answers { mutableListOf<Path.Node>().iterator() }
        every { mock.propertyPath } returns mockPath
        every { mock.message } returns "verplicht"

        val exception = ConstraintViolationException(setOf(mock))
        val response = mapper.toResponse(exception)
        val detail = (response.entity as Problem).detail ?: ""

        // Test bewijst dat het format altijd werkt (geen NPE) bij afwezige path.
        assertTrue(detail.contains("verplicht"), "message moet zichtbaar blijven: $detail")
    }

    @Test
    fun `CRLF in violation-message wordt gestript (CWE-117)`() {
        // CWE-117: log-injection via CRLF in violation-message. Pinned dat sanitize-wrap blijft.
        val exception = ConstraintViolationException(
            setOf(violation("veld", "ongeldig\r\nLevel: ERROR\nfake-line")),
        )

        val response = mapper.toResponse(exception)
        val detail = (response.entity as Problem).detail ?: ""

        org.junit.jupiter.api.Assertions.assertFalse(
            detail.contains("\r") || detail.contains("\n"),
            "CRLF mag niet in detail (log-injection-vector) — gevonden: $detail",
        )
    }

    @Test
    fun `lange detail wordt op 500 chars begrensd (DoS-mitigatie)`() {
        // DoS-amplification: unbounded detail-grootte. Pinned 500-char-grens.
        val longMsg = "a".repeat(2000)
        val exception = ConstraintViolationException(setOf(violation("veld", longMsg)))

        val response = mapper.toResponse(exception)
        val detail = (response.entity as Problem).detail ?: ""

        assertTrue(
            detail.length <= 500,
            "detail moet begrensd zijn op 500 — lengte: ${detail.length}",
        )
    }

    @Test
    fun `file-pad in violation-message wordt geredact`() {
        // Borgt sanitizeClientDetail FILE_PATH_PATTERN-strip op @Pattern-message
        // die per ongeluk paden interpoleert.
        val exception = ConstraintViolationException(
            setOf(violation("config", "fout in /etc/passwd configuratie")),
        )

        val response = mapper.toResponse(exception)
        val detail = (response.entity as Problem).detail ?: ""

        org.junit.jupiter.api.Assertions.assertFalse(
            detail.contains("/etc/passwd"),
            "file-pad mag niet in detail — gevonden: $detail",
        )
    }

    @Test
    fun `meer dan 50 violations worden begrensd (memory-pressure mitigatie)`() {
        // Round 13 L1: take(50) begrenst tussenstring-allocatie. Pin tegen refactor
        // die de grens weghaalt en N=10000 violations toelaat.
        val violations = (1..200).map { violation("veld$it", "fout$it") }.toSet()
        val exception = ConstraintViolationException(violations)

        val response = mapper.toResponse(exception)
        val detail = (response.entity as Problem).detail ?: ""

        // Niet alle 200 violations mogen in de detail staan
        val gevondenAantal = detail.split(";").size
        assertTrue(
            gevondenAantal <= ConstraintViolationExceptionMapper.MAX_VIOLATIONS_IN_DETAIL,
            "max ${ConstraintViolationExceptionMapper.MAX_VIOLATIONS_IN_DETAIL} violations in detail — gevonden: $gevondenAantal",
        )
    }

    private fun violation(propertyName: String, message: String): ConstraintViolation<Any> {
        val violation = mockk<ConstraintViolation<Any>>()
        val path = mockk<Path>()
        val node = mockk<Path.Node>()
        every { node.name } returns propertyName
        every { path.iterator() } answers { mutableListOf(node).iterator() }
        every { path.toString() } returns propertyName
        every { violation.propertyPath } returns path
        every { violation.message } returns message
        return violation
    }
}
