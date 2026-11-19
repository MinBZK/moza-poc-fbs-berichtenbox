package nl.rijksoverheid.moz.fbs.common.exception

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import nl.rijksoverheid.moz.fbs.kern.testhulp.Logvanger
import nl.rijksoverheid.moz.fbs.kern.testhulp.herkenbareDelen
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.Closeable

/**
 * De melding van een Jackson-fout citeert de aangeboden invoer (`from String "…"`, `Unrecognized
 * token '…'`). Wie een ontvanger met een fout aanlevert, mag zo geen stuk van een BSN in de
 * applicatielog zetten, op geen enkel niveau. Het id, het type en het veldpad moeten er wel in staan.
 */
class JsonProcessingExceptionMapperLogTest {

    private val objectMapper = ObjectMapper()
    private val logvanger = Logvanger()

    enum class Soort { BSN, RSIN }

    @AfterEach
    fun sluit() = logvanger.close()

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("gevallen")
    fun `geen stuk van de aangeboden waarde in de log, wel id, type en veldpad`(
        geval: String,
        waarde: String,
        json: String,
        type: TypeReference<*>,
        verwachtPad: String,
    ) {
        val fout = runCatching { objectMapper.readValue(json, type) }.exceptionOrNull() as JsonProcessingException

        // Zonder citaat zou dit geval een mapper die de melding logt niet vangen.
        assertTrue(herkenbareDelen(waarde).any { fout.message!!.contains(it) }, "Jackson citeert de waarde niet meer: ${fout.message}")

        val antwoord = if (fout is MismatchedInputException) {
            MismatchedInputExceptionMapper().toResponse(fout)
        } else {
            JsonProcessingExceptionMapper().toResponse(fout)
        }

        val problem = antwoord.entity as Problem

        val log = logvanger.tekst()
        assertGeenDeelVan(waarde, log)
        assertTrue(log.contains(problem.instance.toString().removePrefix("urn:uuid:")), "het id uit instance hoort in de log: $log")
        assertTrue(log.contains(fout.javaClass.name), "het type hoort in de log: $log")
        assertTrue(log.contains("veld=$verwachtPad,"), "het veldpad hoort in de log: $log")
    }

    @Test
    fun `ook de melding van een cause in de keten blijft uit de log`() {
        val fout = JsonMappingException(
            null as Closeable?,
            "Cannot construct instance from String \"999993653\"",
            IllegalStateException("ontvanger 999993653 onbekend"),
        )

        JsonProcessingExceptionMapper().toResponse(fout)

        val log = logvanger.tekst()
        assertGeenDeelVan("999993653", log)
        assertTrue(log.contains(IllegalStateException::class.java.name), "het type van de cause hoort in het spoor: $log")
    }

    abstract class NietTeBouwen

    @Test
    fun `een model dat Jackson niet kan bouwen, is een eigen fout en komt op error met het spoor`() {
        val fout = runCatching { objectMapper.readValue("{}", NietTeBouwen::class.java) }.exceptionOrNull() as JsonProcessingException

        val (antwoord, log) = Logvanger().use { logvanger ->
            JsonProcessingExceptionMapper().toResponse(fout) to logvanger.tekst()
        }

        assertTrue(log.contains("Jackson-fout buiten de invoer"), log)
        assertTrue(log.contains(fout.javaClass.name), log)
        assertTrue(antwoord.status == 400, "het antwoord blijft een 400")
    }

    private fun assertGeenDeelVan(waarde: String, log: String) {
        assertTrue(log.isNotBlank(), "er hoort een logregel te zijn")

        herkenbareDelen(waarde).forEach { deel ->
            assertFalse(log.contains(deel), "'$deel' uit '$waarde' hoort niet in de log: $log")
        }
    }

    companion object {
        private val GETAL_PER_VELD = object : TypeReference<Map<String, Map<String, Int>>>() {}
        private val JANEE_PER_VELD = object : TypeReference<Map<String, Map<String, Boolean>>>() {}
        private val SOORT_PER_VELD = object : TypeReference<Map<String, Map<String, Soort>>>() {}

        /** Verkeerde lengte, letters erin, en negen cijfers die door de elfproef vallen. */
        private val WAARDEN = listOf("99999365", "99999365x", "999993654")

        @JvmStatic
        fun gevallen() = WAARDEN.flatMap { waarde ->
            listOf(
                Arguments.of("niet-passend type", waarde, """{"ontvanger": {"waarde": "$waarde"}}""", JANEE_PER_VELD, "ontvanger.waarde"),
                Arguments.of("onbekende enumwaarde", waarde, """{"ontvanger": {"type": "$waarde"}}""", SOORT_PER_VELD, "ontvanger.type"),
                // Met een letter vooraan: alleen dan noemt Jackson het hele token in zijn melding.
                Arguments.of("onleesbare JSON", waarde, """{"ontvanger": {"waarde": x$waarde}}""", GETAL_PER_VELD, "geen"),
                Arguments.of("waarde als sleutel", waarde, """{"$waarde": {"x": "abc"}}""", GETAL_PER_VELD, "?.x"),
            )
        }
    }
}
