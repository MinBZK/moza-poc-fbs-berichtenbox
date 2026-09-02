package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Vangnet voor alles waar geen specifiekere mapper voor bestaat: 500 `problem+json` met een
 * correlatie-id in `instance`, zodat een onverwachte fout niet als standaard-foutpagina naar buiten
 * komt en toch in de log terug te vinden is.
 *
 * De melding van de exception blijft uit het antwoord — die kan interne details dragen. De
 * `@Priority` is een extra tiebreaker voor het onwaarschijnlijke geval dat ooit een andere mapper
 * hetzelfde generieke type claimt; hogere waarde is lagere prioriteit.
 *
 * Op `Throwable` en niet op `Exception`: een `OutOfMemoryError` of `StackOverflowError` is bij een
 * fan-out van honderd magazijnen met bijlagen tot 25 MiB geen theoretisch geval, en zou anders langs
 * dit vangnet gaan en als kale foutpagina naar buiten komen — zonder `problem+json`, zonder
 * correlatie-id en zonder deze logregel. Juist dán is de vraag "wat gebeurde er" het lastigst te
 * beantwoorden.
 */
@Provider
@Priority(Priorities.USER + 100)
class UncaughtExceptionMapper : ExceptionMapper<Throwable> {

    private val log = Logger.getLogger(UncaughtExceptionMapper::class.java)

    override fun toResponse(exception: Throwable): Response {
        val foutId = UUID.randomUUID()

        log.errorf(exception, "Onverwachte fout (foutId=%s, type=%s)", foutId, exception.javaClass.name)

        return problemResponse(
            status = Response.Status.INTERNAL_SERVER_ERROR.statusCode,
            title = "Internal Server Error",
            detail = ONVERWACHTE_FOUT_DETAIL,
            foutId = foutId,
        )
    }
}
