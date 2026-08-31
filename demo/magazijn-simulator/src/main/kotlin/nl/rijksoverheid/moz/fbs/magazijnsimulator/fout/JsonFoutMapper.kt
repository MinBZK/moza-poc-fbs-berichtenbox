package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

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
 * De melding van Jackson gaat níét mee naar de client. Die noemt veldnamen, klassenamen en soms een
 * stuk van de aangeboden waarde; dat laatste kan een BSN zijn.
 */
@Provider
@Priority(Priorities.USER - JSON_MAPPER_VOORRANG)
class JsonFoutMapper : ExceptionMapper<JsonProcessingException> {

    override fun toResponse(exception: JsonProcessingException): Response = jsonFoutAntwoord()
}

/** Zie [JsonFoutMapper]; deze dekt het specifiekere geval waarvoor Quarkus zelf al iets meebrengt. */
@Provider
@Priority(Priorities.USER - JSON_MAPPER_VOORRANG)
class NietPassendeInvoerMapper : ExceptionMapper<MismatchedInputException> {

    override fun toResponse(exception: MismatchedInputException): Response = jsonFoutAntwoord()
}

private const val JSON_MAPPER_VOORRANG = 100

private fun jsonFoutAntwoord(): Response = problemResponse(
    status = Response.Status.BAD_REQUEST.statusCode,
    title = "Bad Request",
    detail = "De aangeboden JSON is niet te lezen of past niet op het verwachte formaat",
)
