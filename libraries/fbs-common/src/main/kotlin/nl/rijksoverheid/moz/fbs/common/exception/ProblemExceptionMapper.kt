package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import java.net.URI
import java.util.UUID

/**
 * Vangt alle ongevangen [WebApplicationException]s en zet ze om naar RFC 9457 Problem JSON.
 *
 * - **5xx**: detail wordt gemaskeerd met een generiek bericht plus een UUID-correlation-id
 *   (in zowel `instance` als het errorlog), zodat support een fout kan terugvinden zonder
 *   interne details aan de client te lekken.
 * - **4xx**: de exception-message wordt gesaneerd (controltekens verwijderd, lengte
 *   geknipt, stacktrace-achtige content gefilterd) en in `detail` opgenomen. Zo blijven
 *   eigen `throw WebApplicationException("Header X verplicht", 400)` constructies nuttig,
 *   terwijl Jakarta-defaults en per ongeluk doorgegeven interne messages geen kanaal
 *   krijgen om bijv. stacktraces of SQL-fragmenten door te geven.
 */
@Provider
class ProblemExceptionMapper : ExceptionMapper<WebApplicationException> {

    private val log = Logger.getLogger(ProblemExceptionMapper::class.java)

    override fun toResponse(exception: WebApplicationException): Response {
        val status = exception.response?.status ?: 500
        val title = Response.Status.fromStatusCode(status)?.reasonPhrase ?: "Error"
        val errorId = UUID.randomUUID()

        return if (status >= 500) {
            // Log met correlation-id zodat support de echte oorzaak kan terugvinden,
            // maar expose de interne exception-message niet aan de client.
            log.errorf(exception, "Server error %d (errorId=%s): %s", status, errorId, exception.message)
            maskedServerErrorProblem(errorId = errorId, status = status, title = title)
        } else {
            log.infof("Client error %d (errorId=%s): %s", status, errorId, exception.message)
            problemResponse(
                status = status,
                title = title,
                detail = sanitizeClientDetail(exception.message),
                instance = URI.create("urn:uuid:$errorId"),
            )
        }
    }
}

private const val MAX_CLIENT_DETAIL_LENGTH = 500

// Patronen die duiden op gelekte interne state en dus geen client-content zijn.
private val STACK_FRAME_PATTERN = Regex("""\bat [a-zA-Z_][\w.$]*\([^)]*\)""")
private val FILE_PATH_PATTERN = Regex("""(?:[A-Za-z]:[\\/]|[\\/])(?:[\w.$-]+[\\/])+[\w.$-]+""")
private val CONTROL_CHARS_PATTERN = Regex("""[\u0000-\u001F\u007F]""")

/**
 * Maakt 4xx-detailstring veilig voor blootstelling aan clients:
 * - verwijdert stacktrace-frames (`at pkg.Class.method(File.java:42)`);
 * - verwijdert file-paden (Unix en Windows);
 * - strip controltekens die logs/terminals kunnen vervuilen;
 * - kapt af op 500 tekens.
 *
 * Eigen exception-messages ("Header X ontbreekt") overleven dit ongeschonden; Jakarta-
 * defaults ("HTTP 404 Not Found") ook. Stack-achtige content uit onbedoelde code-paden
 * wordt uitgefilterd.
 */
internal fun sanitizeClientDetail(message: String?): String? {
    if (message.isNullOrBlank()) return null
    val stripped = message
        .replace(STACK_FRAME_PATTERN, "")
        .replace(FILE_PATH_PATTERN, "")
        .replace(CONTROL_CHARS_PATTERN, " ")
        .trim()
        .take(MAX_CLIENT_DETAIL_LENGTH)
    return stripped.ifBlank { null }
}
