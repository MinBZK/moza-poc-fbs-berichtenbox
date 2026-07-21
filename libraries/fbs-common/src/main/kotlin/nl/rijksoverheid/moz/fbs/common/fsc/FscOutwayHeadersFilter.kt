package nl.rijksoverheid.moz.fbs.common.fsc

import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter

/**
 * Zet de FSC-outway-routeringsheaders op een uitgaande magazijn-call.
 *
 * Bewust geen `@Provider`: de grant-hash is per magazijn verschillend, dus wordt de filter
 * per client handmatig geregistreerd in plaats van als globale JAX-RS-provider. De
 * Profiel-client heeft één vaste grant-hash en gebruikt daarom
 * [ProfielFscOutwayHeadersFilter].
 */
class FscOutwayHeadersFilter(private val grantHash: String) : ClientRequestFilter {

    override fun filter(requestContext: ClientRequestContext) {
        FscOutwayHeaders.zet(requestContext, grantHash)
    }
}
