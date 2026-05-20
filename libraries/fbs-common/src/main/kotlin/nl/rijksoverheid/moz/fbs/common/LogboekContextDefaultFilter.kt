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
 * Voorkomt `IllegalArgumentException` uit `addLogboekContextToSpan` als Bean Validation
 * een request afwijst vóór de resource iets vult, of als de service zelf span-management
 * doet (zoals `AanleverResource`).
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
