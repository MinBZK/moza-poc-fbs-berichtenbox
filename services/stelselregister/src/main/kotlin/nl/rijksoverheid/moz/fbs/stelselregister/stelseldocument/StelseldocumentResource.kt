package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.common.exception.FbsFoutException
import nl.rijksoverheid.moz.fbs.common.exception.Foutcode
import nl.rijksoverheid.moz.fbs.stelselregister.api.StelseldocumentApi

@ApplicationScoped
class StelseldocumentResource(
    private val uitgegeven: UitgegevenStelseldocument,
) : StelseldocumentApi {

    override fun getStelseldocument(ifNoneMatch: String?): Response {
        val uitgifte = uitgegeven.geldend() ?: throw FbsFoutException(
            foutcode = Foutcode.TIJDELIJK_NIET_BESCHIKBAAR,
            status = Response.Status.SERVICE_UNAVAILABLE,
            detail = "Er is op dit moment geen geldig stelseldocument beschikbaar.",
            retryAfterSeconden = RETRY_AFTER_SECONDEN,
        )

        val antwoord = if (Etag.komtOvereen(ifNoneMatch, uitgifte.etag)) {
            Response.notModified()
        } else {
            Response.ok(uitgifte.jws, MEDIATYPE)
        }

        return antwoord
            .header(HttpHeaders.ETAG, uitgifte.etag)
            .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
            .header(HttpHeaders.VARY, VARY)
            .build()
    }

    companion object {
        const val MEDIATYPE = "application/jose"

        /**
         * Kort, omdat een afnemer een verwijderd magazijn niet lang mag blijven zien; publiek,
         * omdat het document voor iedereen hetzelfde is en niets gevoeligs bevat.
         */
        const val CACHE_CONTROL = "public, max-age=300"

        /**
         * Het antwoord draagt alleen CORS-headers als het verzoek een `Origin` had. Omdat het
         * publiek cacheable is, zou een gedeelde cache anders het antwoord op een verzoek zonder
         * `Origin` — een probe, een `curl` — aan een browser geven, die het dan weigert.
         */
        const val VARY = "Origin"

        private const val RETRY_AFTER_SECONDEN = 60
    }
}
