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
 * Zet safe defaults op LogboekContext vóórdat resource-code (of een
 * `@Logboek`-CDI-interceptor in services die die nog gebruiken) de echte
 * `dataSubjectId` kan zetten. Voorkomt `IllegalArgumentException` van
 * `ProcessingHandler.addLogboekContextToSpan` als Bean Validation een request
 * afwijst voor de resource-methode iets vult, of als de service zelf
 * span-management doet (zoals `AanleverResource` in berichtenmagazijn).
 *
 * `@Priority(AUTHENTICATION - 100)` (= 900) zorgt voor expliciete vroege
 * ordening: andere filters (auth, logging) draaien daarna, zodat zij op een
 * gevulde LogboekContext kunnen rekenen. Zonder expliciete priority is
 * JAX-RS-volgorde niet gegarandeerd als ooit een tweede ContainerRequestFilter
 * de context muteert.
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
