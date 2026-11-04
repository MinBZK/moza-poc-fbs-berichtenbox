package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.interceptor.AroundInvoke
import jakarta.interceptor.Interceptor
import jakarta.interceptor.InvocationContext
import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.WebApplicationException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.kern.exception.DomainValidationException

/**
 * Legt een weigering in het Logboek Dataverwerkingen vast als correct afgeronde verwerking
 * (`UNSET`) in plaats van als fout. Een 4xx is een uitkomst — "nog niet opgehaald", "bericht
 * onbekend", een verzoek dat niet aan de regels voldoet — en LDV §3.2.2.3 bewaart `ERROR` voor een
 * fout in het interne proces. De LDV-wrapper zet `ERROR` bij elke exceptie die de `@Logboek`-methode
 * verlaat zonder vooraf aangekondigd te zijn; zonder aankondiging staat een 409 vóór of tijdens een
 * ophaalronde als mislukte verwerking in het logboek, en valt die logregel buiten fail-closed.
 *
 * Een interceptor en geen aanroep in elke methode: zo geldt het voor elke `@Logboek`-methode van
 * deze dienst, ook een die later bijkomt. Op `APPLICATION + 1` zit hij binnen de LDV-interceptor
 * (`APPLICATION`) en buiten de validatie (`PLATFORM_AFTER + 800`), dus ook een 400 uit Bean
 * Validation — die vóór de methode ontstaat — komt erlangs.
 */
@Logboek
@Priority(Interceptor.Priority.APPLICATION + 1)
@Interceptor
class LogboekWeigeringInterceptor {

    @Inject
    lateinit var logboekContext: LogboekContext

    @AroundInvoke
    fun kondigWeigeringAan(context: InvocationContext): Any? = logboekContext.meldWeigering { context.proceed() }
}

/** Kondigt een weigering uit [block] aan bij de LDV-wrapper; een 5xx en elke andere exceptie blijven `ERROR`. */
internal inline fun <T> LogboekContext.meldWeigering(block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (isWeigering(e)) expectException(e)

        throw e
    }

/**
 * Of [e] de verwerking als weigering beëindigt: een 4xx, of een overtreden regel die een mapper als
 * 400 teruggeeft (Bean Validation, een domeinregel).
 */
internal fun isWeigering(e: Throwable): Boolean = when (e) {
    is WebApplicationException -> e.response?.status?.let { it in WEIGERINGEN } ?: false
    is ConstraintViolationException, is DomainValidationException -> true
    else -> false
}

private val WEIGERINGEN = 400..499
