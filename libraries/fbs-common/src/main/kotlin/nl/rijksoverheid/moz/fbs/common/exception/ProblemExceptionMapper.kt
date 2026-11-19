package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.Foutspoor
import nl.rijksoverheid.moz.fbs.kern.exception.FrameworkfoutDetail
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
 *   krijgen om bijv. stacktraces of SQL-fragmenten door te geven. Een fout van het framework
 *   zelf krijgt een vaste tekst (zie [clientDetail]).
 */
@Provider
class ProblemExceptionMapper : ExceptionMapper<WebApplicationException> {

    private val log = Logger.getLogger(ProblemExceptionMapper::class.java)

    override fun toResponse(exception: WebApplicationException): Response {
        // Een parameter die niet te converteren is, levert in Jakarta REST een 404 op; het pad
        // bestaat wel, en de spec declareert daarvoor een 400.
        val status = if (FrameworkfoutDetail.isOngeldigeParameter(exception)) {
            Response.Status.BAD_REQUEST.statusCode
        } else {
            exception.response?.status ?: 500
        }

        val title = Response.Status.fromStatusCode(status)?.reasonPhrase ?: "Error"
        val errorId = UUID.randomUUID()

        // Een throw-site die de situatie kent, geeft zijn eigen kenmerk mee; de rest krijgt de
        // terugval van [Foutcode.voorStatus].
        val eigenFoutcode = (exception as? FbsFoutException)?.foutcode
        val foutcode = eigenFoutcode ?: Foutcode.voorStatus(status)

        return if (status >= 500) {
            // Log met correlation-id; `exception.message` blijft eruit (saneer dekt geen
            // niet-numerieke PII zoals namen/e-mail). De stack gaat mee als Foutspoor, zodat
            // support via errorId correleert: de exceptie zelf zou haar message en die van
            // elke cause alsnog in de stacktrace afdrukken.
            log.errorf(
                Foutspoor.van(exception),
                "Server error %d (errorId=%s, type=%s, cause=%s)",
                status,
                errorId,
                exception.javaClass.simpleName,
                exception.cause?.javaClass?.simpleName ?: "geen",
            )
            // Draagt de fout een eigen kenmerk, dan hoort daar de vaste uitleg van dat kenmerk bij
            // in plaats van het generieke masker. Anders zegt een `503 ophalen-mislukt` in zijn
            // `type` "haal opnieuw op" en in zijn `detail` "onverwachte interne fout, bel support".
            // De uitleg komt uit de code, niet uit de exception, dus de maskering blijft intact.
            val detail = eigenFoutcode?.uitleg

            metRetryAfter(
                exception,
                maskedServerErrorProblem(
                    errorId = errorId,
                    status = status,
                    title = title,
                    foutcode = foutcode,
                    detail = detail ?: Foutcode.INTERNE_FOUT.uitleg,
                ),
            )
        } else {
            // 4xx: gesaneerde message naar de client (`sanitizeClientDetail`), maar uit de
            // log gelaten — 4xx-message is vaak user-input en saneer dekt geen niet-numerieke
            // PII. Correlatie via errorId (= `urn:uuid:` in `instance`) + cause-type.
            log.infov(
                exception,
                "Client error {0} (errorId={1}, type={2}, cause={3})",
                status,
                errorId,
                exception.javaClass.simpleName,
                exception.cause?.javaClass?.simpleName ?: "geen",
            )
            metRetryAfter(
                exception,
                problemResponse(
                    status = status,
                    title = title,
                    detail = clientDetail(exception),
                    foutcode = foutcode,
                    instance = URI.create("urn:uuid:$errorId"),
                ),
            )
        }
    }
}

/**
 * Wat een afnemer leest als een andere dienst een verzoek van ons weigerde en de fout ongewijzigd
 * doorkomt. De melding van de REST-client noemt onze klasse- en methodenamen ("when invoking REST
 * Client method: 'nl…MagazijnClient#patchBericht'"), en die horen niet in een foutantwoord.
 */
const val DOORGEGEVEN_WEIGERING_DETAIL = "Een andere dienst in de keten weigerde het verzoek."

/**
 * Het `detail` van een 4xx: de vaste tekst van [FrameworkfoutDetail] bij een fout van het framework,
 * een vaste tekst bij een doorgegeven weigering van een REST-client, geen `detail` bij de
 * standaardmelding, en anders de gesaneerde melding.
 */
private fun clientDetail(exception: WebApplicationException): String? {
    FrameworkfoutDetail.voor(exception)?.let { return it }

    if (isDoorgegevenClientFout(exception)) return DOORGEGEVEN_WEIGERING_DETAIL

    val melding = exception.message

    if (melding != null && FrameworkfoutDetail.isStandaardmelding(melding)) return null

    return sanitizeClientDetail(melding)
}

/**
 * Of [exception] van de REST-client van Quarkus komt. Die markeert zijn fouten met deze interface.
 * Herkend op naam en niet met `is`: de library waarin hij staat brengt een eigen `RuntimeDelegate`
 * mee, en die zou als dependency hier elke `Response.status(...)` buiten Quarkus laten falen.
 */
private fun isDoorgegevenClientFout(exception: WebApplicationException): Boolean =
    generateSequence<Class<*>>(exception.javaClass) { it.superclass }
        .any { klasse -> klasse.interfaces.any { it.name == CLIENT_FOUT_INTERFACE } }

private const val CLIENT_FOUT_INTERFACE = "org.jboss.resteasy.reactive.ResteasyReactiveClientProblem"

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
 * - kapt af op [MAX_CLIENT_DETAIL_LENGTH] tekens.
 *
 * Eigen exception-messages ("Header X ontbreekt") overleven dit ongeschonden. Stack-achtige
 * content uit onbedoelde code-paden wordt uitgefilterd.
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

/**
 * Neemt een `Retry-After` van de oorspronkelijke fout over, op een 5xx en op een 4xx zoals 429. De
 * mapper bouwt een verse response, dus zonder deze stap gaat de aanwijzing "probeer het over N
 * seconden opnieuw" verloren — terwijl dat juist het verschil is tussen een tijdelijke en een
 * blijvende fout.
 *
 * De waarde wordt gefilterd, niet gekopieerd. Een doorgegeven upstream-fout draagt de header van
 * díé upstream, en die bepaalt dan hoe lang ónze afnemers wegblijven: een Profiel-service die
 * onder load `Retry-After: 604800` stuurt, zou een aanleveraar een week stilzetten op gezag van
 * een waarde die wij nooit gekozen hebben. Alleen een geheel aantal seconden binnen een eigen
 * bovengrens gaat mee. Dat sluit meteen de twee vormen uit die het gedeclareerde schema
 * (`type: integer`) breken: een HTTP-datum, en de komma-samenvoeging die `getHeaderString` maakt
 * van twee gelijknamige headers. De aanwijzing van een [FbsFoutException] kiezen we zelf; die valt
 * niet onder de bovengrens.
 */
private fun metRetryAfter(exception: WebApplicationException, respons: Response): Response {
    val seconden = exception.response?.getHeaderString("Retry-After")?.toIntOrNull() ?: return respons
    val bovengrens = if (exception is FbsFoutException) Int.MAX_VALUE else MAX_RETRY_AFTER_SECONDEN

    if (seconden !in 0..bovengrens) return respons

    return Response.fromResponse(respons).header("Retry-After", seconden).build()
}

/**
 * Bovengrens op een overgenomen `Retry-After`. Vijf minuten dekt elke tijdelijke storing waarop
 * een client zinvol kan wachten; alles daarboven is een upstream die zijn eigen ritme oplegt aan
 * onze afnemers.
 */
private const val MAX_RETRY_AFTER_SECONDEN = 300
