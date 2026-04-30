package nl.rijksoverheid.moz.fbs.common.exception

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
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

    // De pad-filtering (SAFE_PATH_PATTERN) is bedoeld als reflected-XSS-defensie:
    // bij Map/JsonNode-deserialisatie zijn keys door de aanvaller bepaald, dus alleen
    // identifier-paden mogen letterlijk in de response terug. Onveilige paden vallen
    // terug op het generieke bericht zonder veld te noemen.

    @Test
    fun `pad met HTML-achtige key valt terug op generiek bericht`() {
        val ex = causeMismatchedInput("""{"<script>alert(1)</script>": {"x": "abc"}}""")

        val problem = MismatchedInputExceptionMapper().toResponse(ex).entity as Problem

        assertEquals("Ongeldige JSON-invoer.", problem.detail)
        assertFalse(problem.detail!!.contains("<")) { "HTML mag niet reflected: ${problem.detail}" }
        assertFalse(problem.detail!!.contains("script"))
    }

    @Test
    fun `pad met spatie in key valt terug op generiek bericht`() {
        val ex = causeMismatchedInput("""{"my key": {"x": "abc"}}""")

        val problem = MismatchedInputExceptionMapper().toResponse(ex).entity as Problem

        assertEquals("Ongeldige JSON-invoer.", problem.detail)
    }

    @Test
    fun `geneste path met identifier-keys wordt wel gehonoreerd`() {
        val ex = causeMismatchedInput("""{"data": {"leeftijd": "niet"}}""")

        val problem = MismatchedInputExceptionMapper().toResponse(ex).entity as Problem

        assertTrue(problem.detail!!.contains("data.leeftijd")) {
            "veld-pad met identifier-keys moet zichtbaar zijn: ${problem.detail}"
        }
    }

    @Test
    fun `pad met array-index valt terug op generiek bericht (huidige join-vorm matcht niet)`() {
        // De helper joined paden met `.` als separator, wat voor array-elementen
        // `data.[0].naam` produceert i.p.v. `data[0].naam`. Het SAFE_PATH_PATTERN
        // verwacht het tweede formaat, dus paden met index vallen terug op
        // het generieke bericht. Dit test borgt dat gedrag (geen reflected key bij
        // bv. een element-niveau parse-fout in een lijst).
        val ex = runCatching {
            objectMapper.readValue(
                """[{"leeftijd": "niet"}]""",
                object : TypeReference<List<Map<String, Int>>>() {},
            )
        }.exceptionOrNull() as? MismatchedInputException
            ?: error("verwachte MismatchedInputException")

        val problem = MismatchedInputExceptionMapper().toResponse(ex).entity as Problem

        assertEquals("Ongeldige JSON-invoer.", problem.detail)
    }
}
