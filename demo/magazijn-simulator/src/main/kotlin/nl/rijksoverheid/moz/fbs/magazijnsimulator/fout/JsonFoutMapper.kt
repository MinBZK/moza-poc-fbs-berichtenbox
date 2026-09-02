package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.core.exc.StreamReadException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Vertaalt onleesbare of niet-passende JSON naar de 400 `problem+json` die de spec voorschrijft.
 *
 * Zonder deze mappers antwoordt Quarkus met een eigen `application/json`-body, en dan is de simulator
 * op zijn foutpad wél van een echt magazijn te onderscheiden — dat vangt hetzelfde geval met zijn
 * eigen Jackson-mappers af.
 *
 * Twee mappers en niet één: JAX-RS kiest op type-nabijheid, en Quarkus brengt zelf al iets mee voor
 * het specifiekere [MismatchedInputException]. Alleen de algemene variant registreren zou dus niets
 * uithalen voor precies het geval dat het vaakst voorkomt — een veld met het verkeerde type, of een
 * enum-waarde die niet bestaat. De `@Priority` beslist waar de type-afstand gelijk is; een lagere
 * waarde wint.
 *
 * Een JSON-body die halverwege afbreekt bereikt deze mappers niet: Quarkus vertaalt zo'n parse-fout
 * al vóór de mapper-keuze naar een `WebApplicationException`. Die komt via
 * [ProblemExceptionMapper] alsnog als `problem+json` naar buiten.
 *
 * **Niet elke Jackson-fout komt van de aanroeper.** Dezelfde exception-familie treedt op bij het
 * *schrijven* van een antwoord — een mapping die na een spec-wijziging niet meer serialiseert. Als
 * 400 gerapporteerd zou dat een programmeerfout aan de aanroeper toeschrijven en, erger, niet te
 * onderscheiden zijn van de gesimuleerde weigering die dit magazijn ook kan geven. Alleen de
 * lees-kant is een clientfout; de rest is een 500 met een correlatie-id.
 *
 * Het onderscheid loopt daarom over de leeskant als geheel en niet over twee losse subtypes.
 * [StreamConstraintsException] — de body overschrijdt een van Jacksons eigen grenzen, bijvoorbeeld
 * twintig miljoen tekens in één string — hangt rechtstreeks onder [JsonProcessingException] en
 * niet onder [StreamReadException]. Op twee subtypes toetsen liet zo'n aanlevering als 500 naar
 * buiten komen, terwijl het echte magazijn er 400 op geeft.
 *
 * De melding van Jackson gaat níét mee naar de client. Die noemt veldnamen, klassenamen en soms een
 * stuk van de aangeboden waarde; dat laatste kan een BSN zijn. In de log staat hij wel — daar is hij
 * nodig om te zien wát er niet paste.
 */
@Provider
@Priority(Priorities.USER - JSON_MAPPER_VOORRANG)
class JsonFoutMapper : ExceptionMapper<JsonProcessingException> {

    override fun toResponse(exception: JsonProcessingException): Response =
        if (exception is StreamReadException ||
            exception is StreamConstraintsException ||
            exception is MismatchedInputException
        ) {
            jsonFoutAntwoord(exception)
        } else {
            serialisatieFoutAntwoord(exception)
        }
}

/** Zie [JsonFoutMapper]; deze dekt het specifiekere geval waarvoor Quarkus zelf al iets meebrengt. */
@Provider
@Priority(Priorities.USER - JSON_MAPPER_VOORRANG)
class NietPassendeInvoerMapper : ExceptionMapper<MismatchedInputException> {

    override fun toResponse(exception: MismatchedInputException): Response = jsonFoutAntwoord(exception)
}

private const val JSON_MAPPER_VOORRANG = 100

private val log: Logger = Logger.getLogger(JsonFoutMapper::class.java)

private fun jsonFoutAntwoord(exception: JsonProcessingException): Response {
    val foutId = UUID.randomUUID()

    // Op info, net als de clientfouten in ProblemExceptionMapper: zonder uitgezonden regel is het
    // `instance`-id uit het antwoord nergens terug te vinden, en dat is waar iemand mee aanklopt.
    log.infof(exception, "Onleesbare of niet-passende JSON (foutId=%s)", foutId)

    return problemResponse(
        status = Response.Status.BAD_REQUEST.statusCode,
        title = "Bad Request",
        detail = "De aangeboden JSON is niet te lezen of past niet op het verwachte formaat",
        foutId = foutId,
    )
}

private fun serialisatieFoutAntwoord(exception: JsonProcessingException): Response {
    val foutId = UUID.randomUUID()

    log.errorf(exception, "Antwoord niet te serialiseren (foutId=%s, type=%s)", foutId, exception.javaClass.name)

    return problemResponse(
        status = Response.Status.INTERNAL_SERVER_ERROR.statusCode,
        title = "Internal Server Error",
        detail = ONVERWACHTE_FOUT_DETAIL,
        foutId = foutId,
    )
}
