package nl.rijksoverheid.moz.fbs.common

import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext

/**
 * Zet safe defaults op LogboekContext voordat de @Logboek CDI interceptor draait.
 * Voorkomt IllegalArgumentException als Bean Validation een request afwijst voor
 * de resource-methode de echte dataSubjectId kan zetten.
 */
@Provider
class LogboekContextDefaultFilter : ContainerRequestFilter {

    @Inject
    lateinit var logboekContext: LogboekContext

    override fun filter(requestContext: ContainerRequestContext) {
        logboekContext.dataSubjectId = "unknown"
        logboekContext.dataSubjectType = "system"
    }
}
