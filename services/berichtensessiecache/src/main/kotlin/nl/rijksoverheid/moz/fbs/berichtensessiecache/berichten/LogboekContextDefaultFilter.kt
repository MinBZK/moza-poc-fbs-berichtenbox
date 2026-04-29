package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext

/**
 * Sets safe defaults on LogboekContext before the @Logboek CDI interceptor runs.
 * This prevents IllegalArgumentException when Bean Validation rejects a request
 * before the resource method body can set the actual dataSubjectId.
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
