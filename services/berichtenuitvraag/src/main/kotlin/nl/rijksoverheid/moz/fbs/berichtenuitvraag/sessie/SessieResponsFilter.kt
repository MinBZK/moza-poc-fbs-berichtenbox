package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ApiInfo

/**
 * De headers die het sessiecontract op een antwoord eist, en die een resource met een gegenereerd
 * return-type niet zelf kan zetten.
 *
 * - Elke 401 krijgt de challenge van het sessieschema: een 401 zonder `WWW-Authenticate` mag
 *   niet (RFC 9110 §15.5.2). De sessie is het enige authenticatieschema van deze API, dus elke 401
 *   hoort erbij, uit het filter of uit de cache. Bij de overstap naar een token wordt dit
 *   `Bearer realm="…", error="invalid_token"`.
 * - Een gestarte sessie krijgt een `Location`. Die wijst naar `/sessies/huidige`, een pad zonder
 *   geheim: welke sessie dat is, zegt de `X-Sessie` van het verzoek.
 */
@Provider
class SessieResponsFilter : ContainerResponseFilter {

    override fun filter(request: ContainerRequestContext, response: ContainerResponseContext) {
        if (response.status == UNAUTHORIZED) response.headers.putSingle(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE)

        if (isGestart(request, response)) response.headers.putSingle(HttpHeaders.LOCATION, HUIDIGE_SESSIE_PAD)
    }

    // 200 of 201: de gedeelde CreatedStatusFilter kan vóór of na dit filter draaien.
    private fun isGestart(request: ContainerRequestContext, response: ContainerResponseContext): Boolean =
        request.method == HttpMethod.POST &&
            request.uriInfo.path.removePrefix("/") == "sessies" &&
            response.status in GESLAAGD

    companion object {
        const val CHALLENGE = "X-Sessie realm=\"berichtenuitvraag\""
        const val HUIDIGE_SESSIE_PAD = "${ApiInfo.BASE_PATH}/sessies/huidige"

        private const val UNAUTHORIZED = 401
        private val GESLAAGD = 200..201
    }
}
