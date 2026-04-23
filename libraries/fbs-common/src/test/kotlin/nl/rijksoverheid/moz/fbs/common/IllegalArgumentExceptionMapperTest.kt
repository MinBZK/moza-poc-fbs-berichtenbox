package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IllegalArgumentExceptionMapperTest {

    private val mapper = IllegalArgumentExceptionMapper()

    @Test
    fun `behandelt generieke IAE als 500 bug en maskeert boodschap achter vaste tekst`() {
        val response = mapper.toResponse(IllegalArgumentException("No enum constant com.internal.Secret.FOO"))

        assertEquals(500, response.status)
        assertEquals("application/problem+json", response.mediaType.toString())

        val problem = response.entity as Problem
        assertEquals("Internal Server Error", problem.title)
        assertEquals(500, problem.status)
        // Exacte match op de gemaskeerde detailtekst: veiliger dan `assertNotEquals` op de
        // originele boodschap, omdat die ook per ongeluk een andere lekkage zou toestaan.
        assertEquals(
            "Er is een interne fout opgetreden. Vermeld errorId bij contact met support.",
            problem.detail,
        )
        assertNotNull(problem.instance)
        assertTrue(problem.instance!!.toString().startsWith("urn:uuid:"))
    }
}
