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

    /**
     * Het headerpaar voor één outway-call. Losgetrokken van het transport omdat niet elke caller
     * een JAX-RS-client is: de downstream-aflevering van CloudEvents gebruikt
     * `java.net.http.HttpClient` en zou het contract anders moeten dupliceren.
     */
    fun headers(grantHash: String): Map<String, String> = mapOf(
        GRANT_HASH_HEADER to grantHash,
        TRANSACTION_ID_HEADER to UuidV7.generate().toString(),
    )

    fun zet(requestContext: ClientRequestContext, grantHash: String) {
        val paar = headers(grantHash)

        paar.forEach { (naam, waarde) -> requestContext.headers.putSingle(naam, waarde) }

        // Zonder deze transaction-id in de app-log is een call niet terug te vinden in de
        // outway-/inway-logs, die 'm ongewijzigd doorgeven. Log alleen de host, nooit het
        // volledige URI: sommige callers (Profiel-service) dragen een BSN in het pad, en
        // deze regel logt bij DEBUG — dus een pad of query hier zou dat BSN naar de
        // applicatielog schrijven. De host identificeert de outway afdoende.
        log.debugf(
            "FSC-outway-call naar %s: Fsc-Transaction-Id=%s",
            requestContext.uri.host,
            paar[TRANSACTION_ID_HEADER],
        )
    }
}
