package nl.rijksoverheid.moz.fbs.common.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class UncaughtExceptionMapperTest {

    private val mapper = UncaughtExceptionMapper()

    @Test
    fun `vertaalt willekeurige exception naar 500 problem zonder details te lekken`() {
        val exception = IOException("ClickHouse onbereikbaar: connection refused at /1.2.3.4:8123")

        val response = mapper.toResponse(exception)

        assertEquals(500, response.status)
        assertEquals("application/problem+json", response.mediaType.toString())

        val problem = response.entity as Problem
        assertEquals("Internal Server Error", problem.title)
        assertEquals(500, problem.status)
        assertEquals(
            "Er is een onverwachte interne fout opgetreden. Vermeld errorId bij contact met support.",
            problem.detail,
        )
        assertNotNull(problem.instance)
        assertTrue(problem.instance!!.toString().startsWith("urn:uuid:"))
    }
}
