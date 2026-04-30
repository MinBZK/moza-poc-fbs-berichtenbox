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
