package nl.rijksoverheid.moz.fbs.common.exception

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.kern.exception.Foutspoor
import nl.rijksoverheid.moz.fbs.kern.exception.Veldpad
import org.jboss.logging.Logger
import java.net.URI
import java.util.UUID

private val log: Logger = Logger.getLogger("nl.rijksoverheid.moz.fbs.common.exception.JsonProcessingExceptionMapper")

// Veilig pad: Java-identifier segmenten, gescheiden door `.`, optioneel met array-indexen.
// Weigert o.a. HTML, control-chars, spaties — alles wat attacker-controlled keys kenmerkt.
private val SAFE_PATH_PATTERN = Regex("""^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*|\[\d+])*$""")

internal fun jsonProcessingExceptionToResponse(exception: JsonProcessingException): Response {
    val errorId = UUID.randomUUID()

    // De melding van Jackson blijft uit de log, op elk niveau: `Cannot deserialize value … from
    // String "<invoer>"` zet een stuk van de aangeboden waarde erin, en dat kan een BSN zijn. Type,
    // veldpad en positie zeggen wat er niet paste; het spoor houdt de stack van de keten. Op info,
    // als de andere clientfouten: op debug staat het id uit `instance` in productie nergens.
    log.infof(
        Foutspoor.van(exception),
        "JSON-invoer niet te lezen (errorId=%s, type=%s, veld=%s, positie=%s)",
        errorId,
        exception.javaClass.name,
        (exception as? JsonMappingException)?.let(::veldpadVoorLog) ?: "geen",
        exception.location?.let { "${it.lineNr}:${it.columnNr}" } ?: "onbekend",
    )

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

    return problemResponse(
        status = 400,
        title = "Bad Request",
        detail = detail,
        foutcode = Foutcode.ONGELDIG_VERZOEK,
        instance = URI.create("urn:uuid:$errorId"),
    )
}

private fun veldpadVoorLog(exception: JsonMappingException): String =
    Veldpad.van(exception.path.map { it.fieldName ?: it.index })

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
