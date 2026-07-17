package nl.rijksoverheid.moz.fbs.common.fsc

import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import org.jboss.logging.Logger

/**
 * Zet de FSC-outway-routeringsheaders op een uitgaande magazijn-call. De OpenFSC-outway kiest
 * de doel-inway op `Fsc-Grant-Hash` (niet op het pad) en `fsc-outway serve` eist een
 * `Fsc-Transaction-Id` in UUID-v7-vorm; zonder deze headers antwoordt de outway met
 * "service not found" resp. "invalid uuid version, must be v7".
 *
 * Bewust géén `@Provider`: de grant-hash is per magazijn verschillend, dus wordt de filter
 * per client handmatig geregistreerd in plaats van als globale JAX-RS-provider.
 */
class FscOutwayHeadersFilter(private val grantHash: String) : ClientRequestFilter {

    override fun filter(requestContext: ClientRequestContext) {
        val transactionId = UuidV7.generate()

        requestContext.headers.putSingle("Fsc-Grant-Hash", grantHash)
        requestContext.headers.putSingle("Fsc-Transaction-Id", transactionId.toString())

        // Zonder deze transaction-id in de app-log is een call niet terug te vinden in de
        // outway-/inway-logs, die 'm ongewijzigd doorgeven.
        log.debugf(
            "FSC-outway-call naar %s: Fsc-Transaction-Id=%s",
            requestContext.uri,
            transactionId,
        )
    }

    companion object {
        private val log = Logger.getLogger(FscOutwayHeadersFilter::class.java)
    }
}
