package nl.rijksoverheid.moz.fbs.common.fsc

import jakarta.ws.rs.client.ClientRequestContext
import org.jboss.logging.Logger

/**
 * Het FSC-outway-headercontract op één plek. De OpenFSC-outway kiest de doel-inway op
 * `Fsc-Grant-Hash` (niet op het pad) en `fsc-outway serve` eist een `Fsc-Transaction-Id` in
 * UUID-v7-vorm; zonder deze headers antwoordt de outway met "service not found" resp.
 * "invalid uuid version, must be v7".
 *
 * Meerdere clients sturen deze headers (magazijn-calls per inschrijving, de Profiel-call),
 * elk met een eigen manier om aan hun grant-hash te komen. Die herkomst verschilt; het
 * contract niet — daarom staat het hier en niet in de afzonderlijke filters.
 */
object FscOutwayHeaders {

    const val GRANT_HASH_HEADER = "Fsc-Grant-Hash"
    const val TRANSACTION_ID_HEADER = "Fsc-Transaction-Id"

    private val log = Logger.getLogger(FscOutwayHeaders::class.java)

    fun zet(requestContext: ClientRequestContext, grantHash: String) {
        val transactionId = UuidV7.generate()

        requestContext.headers.putSingle(GRANT_HASH_HEADER, grantHash)
        requestContext.headers.putSingle(TRANSACTION_ID_HEADER, transactionId.toString())

        // Zonder deze transaction-id in de app-log is een call niet terug te vinden in de
        // outway-/inway-logs, die 'm ongewijzigd doorgeven.
        log.debugf(
            "FSC-outway-call naar %s: Fsc-Transaction-Id=%s",
            requestContext.uri,
            transactionId,
        )
    }
}
