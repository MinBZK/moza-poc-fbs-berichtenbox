package nl.rijksoverheid.moz.berichtensessiecache

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.berichtensessiecache.api.model.Problem
import org.jboss.logging.Logger
import java.net.URI

/**
 * Mapt Jackson-deserialisatiefouten (bv. ongeldige enum-waardes zoals
 * `{"status":"bekeken"}` waar alleen `gelezen`/`ongelezen` valid zijn) naar
 * een 400 Problem+JSON-response. Zonder deze mapper geeft Quarkus een
 * 400 met `application/json`, wat de NL API Design Rules schendt.
 */
@Provider
class JsonProcessingExceptionMapper : ExceptionMapper<JsonProcessingException> {

    private val log = Logger.getLogger(JsonProcessingExceptionMapper::class.java)

    override fun toResponse(exception: JsonProcessingException): Response {
        log.debugf("JSON-deserialisatiefout: %s", exception.originalMessage)

        val problem = Problem()
        problem.type = URI.create("about:blank")
        problem.status = 400
        problem.title = "Bad Request"
        problem.detail = formatDetail(exception)

        return Response.status(400)
            .type(PROBLEM_JSON)
            .entity(problem)
            .build()
    }

    private fun formatDetail(exception: JsonProcessingException): String = when (exception) {
        is InvalidFormatException -> {
            val field = exception.path.joinToString(".") { it.fieldName ?: "[${it.index}]" }
            val value = exception.value
            "Ongeldige waarde voor veld '$field': '$value'"
        }
        else -> exception.originalMessage ?: "Ongeldige JSON-body"
    }

    companion object {
        private val PROBLEM_JSON = MediaType.valueOf("application/problem+json")
    }
}
