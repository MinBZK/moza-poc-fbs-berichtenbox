package nl.rijksoverheid.moz.fbs.common

import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext

/**
 * Priority-anker voor [LogboekContextDefaultFilter]. Andere filters die vóór
 * óf ná de LDV-context-defaults willen draaien gebruiken `LDV_CONTEXT_DEFAULT_PRIORITY ± n`
 * i.p.v. raw arithmetic op `Priorities.AUTHENTICATION` — voorkomt drift tussen
 * filters die om dezelfde "vroege" slot vragen.
 */
const val LDV_CONTEXT_DEFAULT_PRIORITY = Priorities.AUTHENTICATION - 100

/**
 * Zet safe defaults op LogboekContext vóór resource-code de echte `dataSubjectId` zet.
 * Zonder deze defaults levert een request dat vóór de resource sneuvelt — Bean Validation
 * wijst het af, of de service doet zelf span-management zoals `AanleverResource` — een
 * logregel op met lege betrokkene-velden, die de wrapper als onvolledige context
 * wegschrijft met een waarschuwing.
 *
 * Vroege [LDV_CONTEXT_DEFAULT_PRIORITY] zodat latere filters op een gevulde context rekenen.
 */
@Provider
@Priority(LDV_CONTEXT_DEFAULT_PRIORITY)
class LogboekContextDefaultFilter : ContainerRequestFilter {

    @Inject
    lateinit var logboekContext: LogboekContext

    override fun filter(requestContext: ContainerRequestContext) {
        logboekContext.dataSubjectId = "unknown"
        logboekContext.dataSubjectType = "system"
    }
}
