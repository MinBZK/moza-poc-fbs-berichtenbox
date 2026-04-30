package nl.rijksoverheid.moz.fbs.common.exception

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

private val log: Logger = Logger.getLogger("nl.rijksoverheid.moz.fbs.common.exception.JsonProcessingExceptionMapper")

// Veilig pad: Java-identifier segmenten, gescheiden door `.`, optioneel met array-indexen.
// Weigert o.a. HTML, control-chars, spaties — alles wat attacker-controlled keys kenmerkt.
private val SAFE_PATH_PATTERN = Regex("""^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*|\[\d+])*$""")

internal fun jsonProcessingExceptionToResponse(exception: JsonProcessingException): Response {
    // Jackson-boodschappen bevatten vaak class-/package-namen en input fragments. Nooit
    // verbatim terug — wel intern loggen. Debug-niveau: malformed JSON is een client-fout
    // en veroorzaakt anders onnodig log-volume bij een kapotte of kwaadwillende client.
    log.debugf(exception, "JSON-deserialisatiefout (detail niet naar client): %s", exception.originalMessage)

    val detail = when (exception) {
        is MismatchedInputException -> {
            val rawPath = exception.path.joinToString(".") { it.fieldName ?: "[${it.index}]" }
            // Alleen toevoegen als het pad uit veldnamen van ons model komt (niet
            // attacker-controlled keys bij Map/JsonNode deserialisatie). Anders kunnen
            // payloads als `{"<script>": 1}` reflected in de response belanden.
            if (SAFE_PATH_PATTERN.matches(rawPath)) {
                "Ongeldige JSON-invoer voor veld '$rawPath'."
            } else {
                "Ongeldige JSON-invoer."
            }
        }
        else -> "Ongeldige JSON-invoer."
    }

    return problemResponse(status = 400, title = "Bad Request", detail = detail)
}

/**
 * Mapt generieke Jackson [JsonProcessingException]s naar een 400 Problem JSON.
 * De interne Jackson-boodschap wordt niet naar de client gelekt.
 */
@Provider
@Priority(Priorities.USER)
class JsonProcessingExceptionMapper : ExceptionMapper<JsonProcessingException> {
    override fun toResponse(exception: JsonProcessingException): Response =
        jsonProcessingExceptionToResponse(exception)
}

/**
 * Mapt Jackson's [MismatchedInputException] (bv. ontbrekende verplichte velden)
 * naar een 400 Problem JSON response. Deze mapper is specifieker dan
 * [JsonProcessingExceptionMapper] en overstemt de ingebouwde
 * `BuiltinMismatchedInputExceptionMapper` van quarkus-rest-jackson.
 */
@Provider
@Priority(Priorities.USER)
class MismatchedInputExceptionMapper : ExceptionMapper<MismatchedInputException> {
    override fun toResponse(exception: MismatchedInputException): Response =
        jsonProcessingExceptionToResponse(exception)
}
