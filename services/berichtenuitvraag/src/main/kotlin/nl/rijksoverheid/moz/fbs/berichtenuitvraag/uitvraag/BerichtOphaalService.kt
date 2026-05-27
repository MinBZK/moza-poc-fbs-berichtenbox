package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotAuthorizedException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * Bericht-detail uit de sessiecache; bijlagen rechtstreeks uit het magazijn
 * (passthrough). `haalBijlage` levert `(mimeType, bytes)`: de resource (Task 9)
 * legt het mimeType op een request-property zodat [BijlageContentTypeFilter]
 * de response-`Content-Type` kan overrulen.
 *
 * Bytes worden in een ByteArray geladen — bij PoC-MAX 1 MiB is dat acceptabel.
 * Bij grotere bijlagen later: InputStream-streaming.
 *
 * Magazijn-fouten worden status-behoudend doorgegeven: 404/401/403 mappen 1-op-1
 * (zodat de OpenAPI-belofte van 404 en de LDV-audittrail klopt), 5xx mapt naar
 * 502 (downstream-faal). De catch-all `InternalServerErrorException` blijft
 * gereserveerd voor échte malformed responses (geen Content-Type bij 2xx).
 */
@ApplicationScoped
class BerichtOphaalService(
    @RestClient private val sessiecache: SessiecacheClient,
    @RestClient private val magazijn: MagazijnClient,
) {
    fun haalBericht(xOntvanger: String, berichtId: UUID): Bericht =
        sessiecache.bericht(xOntvanger, berichtId)

    fun haalBijlage(xOntvanger: String, berichtId: UUID, bijlageId: UUID): Pair<String, ByteArray> {
        // De Quarkus REST-client gooit zelf al een WAE bij upstream >=400 (ook
        // wanneer de signature `Response` retourneert via de default exception-
        // mapper). Vang dat hier en hermap zodat 401/403/404 1-op-1 propageren
        // en 5xx als 502 voor de client zichtbaar wordt.
        val response = try {
            magazijn.bijlage(xOntvanger, berichtId, bijlageId)
        } catch (e: WebApplicationException) {
            throw magazijnFout(e.response?.status ?: 502)
        }

        try {
            if (response.status >= 400) throw magazijnFout(response.status)

            // Pak de raw Content-Type-header (niet `response.mediaType`, die
            // parsed naar `MediaType?` en geeft null bij een ongeldig MIME-type;
            // de fail-closed-fallback in [BijlageContentTypeFilter] hoort die
            // case af te handelen). Volledig ontbrekende header is hier wel
            // een echte upstream-bug → 502.
            val mimeType = response.getHeaderString("Content-Type")
                ?: throw WebApplicationException("magazijn-bijlage zonder Content-Type", Response.Status.BAD_GATEWAY)
            val bytes = response.readEntity(ByteArray::class.java)

            return mimeType to bytes
        } finally {
            response.close()
        }
    }

    private fun magazijnFout(status: Int): WebApplicationException = when (status) {
        401 -> NotAuthorizedException("magazijn-bijlage 401")
        403 -> ForbiddenException("magazijn-bijlage 403")
        404 -> NotFoundException("magazijn-bijlage 404")
        in 400..499 -> WebApplicationException("magazijn-bijlage 4xx ($status)", status)
        else -> WebApplicationException("magazijn-bijlage 5xx ($status)", Response.Status.BAD_GATEWAY)
    }
}
