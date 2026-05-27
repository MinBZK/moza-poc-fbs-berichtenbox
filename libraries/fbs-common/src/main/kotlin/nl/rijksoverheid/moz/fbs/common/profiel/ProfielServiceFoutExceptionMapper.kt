package nl.rijksoverheid.moz.fbs.common.profiel

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.exception.Problem
import nl.rijksoverheid.moz.fbs.common.exception.ProblemMediaType
import org.jboss.logging.Logger
import java.net.URI

/**
 * Vertaalt [ProfielServiceFoutException] naar HTTP 503 + Problem+JSON met
 * `Retry-After: 30`. Voor de fout-taxonomie zie [ProfielServiceFoutException].
 *
 * `instance = urn:uuid:<errorId>` koppelt de client-response aan de applicatielog
 * (consistent met `UncaughtExceptionMapper`/`ProblemExceptionMapper`/etc.) zodat
 * support een 503 kan terugzoeken zonder dat de client interne details ziet.
 *
 * De `errorId` wordt door [ProfielServiceFoutException] zelf gedragen (gegenereerd
 * op constructie) zodat de service-laag dezelfde id kan loggen in cleanup-paden;
 * support kan dan vanuit een client-response alle service-side log-regels rond
 * de fout vinden, niet alleen deze mapper-regel.
 */
@Provider
class ProfielServiceFoutExceptionMapper : ExceptionMapper<ProfielServiceFoutException> {

    private val log = Logger.getLogger(ProfielServiceFoutExceptionMapper::class.java)

    override fun toResponse(exception: ProfielServiceFoutException): Response {
        val errorId = exception.errorId

        // CONFIG_DRIFT is geen Profiel-storing maar een eigen config-fout (alle opt-in
        // OINs onbekend bij magazijn-config). Retry over 30s lost het niet op; geef een
        // eigen problem-type + 500 zodat client niet onnodig retry'd én support direct
        // weet welk type incident speelt. Service-laag vangt CONFIG_DRIFT meestal al
        // af in een SSE-OPHALEN_FOUT-pad; deze mapper-tak is defense-in-depth voor
        // paden waar het exception buiten de service-catch doorlekt.
        if (exception.categorie == ProfielServiceFoutException.Categorie.CONFIG_DRIFT) {
            log.errorf(
                "Config-drift naar mapper-pad doorgelekt (errorId=%s) — service-catch zou dit normaal opvangen",
                errorId,
            )

            val problem = Problem(
                type = URI.create("https://moza.nl/problems/configuratie-mismatch"),
                title = "Configuratie-mismatch tussen profielen en magazijnen",
                status = 500,
                detail = "Geen ophaling mogelijk wegens configuratie. Retry heeft geen effect — contact beheerder.",
                instance = URI.create("urn:uuid:$errorId"),
            )

            return Response.status(500)
                .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
                .entity(problem)
                .build()
        }

        // Bewust geen full stacktrace via .warnf(exception, …): de cause-chain van
        // lower-level HTTP-clients kan in randgevallen de upstream-URL bevatten,
        // die de BSN/RSIN/KVK in het pad heeft (extern Profiel-contract). Log alleen
        // de categorie + cause-type voor diagnose; errorId verbindt naar deze response.
        // Categorie als gestructureerd veld zodat log-aggregatie op fault-mode kan filteren.
        val httpStatusLabel = exception.httpStatus?.toString() ?: "n.v.t."
        log.warnf(
            "Profiel-service onbereikbaar (errorId=%s, categorie=%s, httpStatus=%s, cause=%s)",
            errorId,
            exception.categorie.name,
            httpStatusLabel,
            exception.cause?.javaClass?.simpleName ?: "geen",
        )

        val problem = Problem(
            type = URI.create("https://moza.nl/problems/profiel-service-onbereikbaar"),
            title = "Profiel-service tijdelijk niet beschikbaar",
            status = 503,
            detail = "De toestemmingscontrole kon niet uitgevoerd worden. Probeer over 30 seconden opnieuw.",
            instance = URI.create("urn:uuid:$errorId"),
        )

        return Response.status(503)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .header("Retry-After", "30")
            .build()
    }
}
