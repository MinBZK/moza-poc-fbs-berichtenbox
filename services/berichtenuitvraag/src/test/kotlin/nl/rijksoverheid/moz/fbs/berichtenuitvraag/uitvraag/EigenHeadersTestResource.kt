package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response

/**
 * Test-only endpoint dat zelf een security-header en een eigen `Cache-Control` zet.
 *
 * Zonder zo'n endpoint is end-to-end niet te zien óf de registratie een bestaande header
 * vervangt: is zij de enige schrijver, dan levert toevoegen precies dezelfde response als
 * vervangen. Juist dat verschil is waar deze constructie voor bestaat. Bestaat alleen in
 * test-sources.
 */
@Path("/test-only/eigen-headers")
@ApplicationScoped
class EigenHeadersTestResource {

    @GET
    fun get(): Response =
        Response.ok("x")
            .header("X-Frame-Options", "SAMEORIGIN")
            .header("Content-Security-Policy", "frame-ancestors 'self'")
            .header("Cache-Control", "max-age=60")
            .build()
}
