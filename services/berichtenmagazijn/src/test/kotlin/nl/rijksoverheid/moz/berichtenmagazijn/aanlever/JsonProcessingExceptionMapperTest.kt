package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import nl.rijksoverheid.moz.fbs.common.JsonProcessingExceptionMapper
import nl.rijksoverheid.moz.fbs.common.MismatchedInputExceptionMapper
import nl.rijksoverheid.moz.fbs.common.Problem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonProcessingExceptionMapperTest {

    private val objectMapper = ObjectMapper()

    private fun causeMismatchedInput(json: String): MismatchedInputException =
        runCatching {
            objectMapper.readValue(
                json,
                object : TypeReference<Map<String, Map<String, Int>>>() {},
            )
        }.exceptionOrNull() as? MismatchedInputException
            ?: error("verwachte MismatchedInputException")

    @Test
    fun `MismatchedInputException wordt gemapt naar 400 met veld-pad`() {
        val ex = causeMismatchedInput("""{"data": {"leeftijd": "niet-een-getal"}}""")

        val response = MismatchedInputExceptionMapper().toResponse(ex)

        assertEquals(400, response.status)
        assertEquals("application/problem+json", response.mediaType.toString())
        val problem = response.entity as Problem
        assertEquals("Bad Request", problem.title)
        assertEquals(400, problem.status)
        assertNotNull(problem.detail)
        assertTrue(problem.detail!!.contains("leeftijd")) { "veldpad moet aanwezig zijn: ${problem.detail}" }
    }

    @Test
    fun `MismatchedInputException lekt geen Jackson-originalMessage of class paths`() {
        val ex = causeMismatchedInput("""{"data": {"x": "abc"}}""")
        val originalMsg = ex.originalMessage ?: ""

        val response = MismatchedInputExceptionMapper().toResponse(ex)
        val problem = response.entity as Problem

        // Jackson originalMessage bevat typisch class-namen zoals `java.lang.Integer`
        // of package-paden. De client mag die niet zien.
        assertFalse(problem.detail!!.contains("java.lang"))
        assertFalse(problem.detail!!.contains("com.fasterxml"))
        assertFalse(problem.detail!! == originalMsg) {
            "detail mag niet identiek zijn aan originalMessage"
        }
    }

    @Test
    fun `generieke JsonProcessingException zonder pad retourneert generiek bericht`() {
        val ex = runCatching {
            objectMapper.readTree("{ not valid json")
        }.exceptionOrNull() as com.fasterxml.jackson.core.JsonProcessingException

        val response = JsonProcessingExceptionMapper().toResponse(ex)

        assertEquals(400, response.status)
        val problem = response.entity as Problem
        assertEquals("Bad Request", problem.title)
        assertEquals("Ongeldige JSON-invoer.", problem.detail)
    }
}
