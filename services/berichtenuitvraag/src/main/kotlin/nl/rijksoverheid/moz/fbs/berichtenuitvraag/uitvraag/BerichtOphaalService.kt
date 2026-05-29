package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * Bericht-detail uit de sessiecache; bijlagen rechtstreeks uit het magazijn
 * (passthrough). `haalBijlage` levert `(mimeType, bytes)`: de resource legt het
 * mimeType op een request-property zodat [BijlageContentTypeFilter] de
 * response-`Content-Type` kan overrulen.
 *
 * Bytes worden volledig in een ByteArray geladen; bij de maximale bijlagegrootte
 * van 1 MiB blijft het geheugengebruik per request begrensd.
 *
 * Magazijn-fouten worden status-behoudend doorgegeven: 404/401/403 mappen 1-op-1
 * (zodat de OpenAPI-belofte van 404 en de LDV-audittrail klopt), 5xx mapt naar
 * 502 (downstream-faal). De catch-all `InternalServerErrorException` blijft
 * gereserveerd voor échte malformed responses (geen Content-Type bij 2xx).
 *
 * Multi-magazijn routering (bijlage-download): de sessiecache levert per bericht
 * het bron-`magazijnId`. We halen eerst het bericht-detail op (we vertrouwen de
 * cache, niet een client-meegegeven id — anders zou een aanvaller bijlages uit
 * een ander magazijn kunnen opvragen). Daarna routeert [MagazijnRouter] naar de
 * juiste magazijn-URL voor het downloaden van de bytes.
 */
@ApplicationScoped
class BerichtOphaalService(
    @RestClient private val sessiecache: SessiecacheClient,
    private val magazijnRouter: MagazijnRouter,
) {
    fun haalBericht(xOntvanger: String, berichtId: UUID): Bericht =
        sessiecache.bericht(xOntvanger, berichtId)

    fun haalBijlage(xOntvanger: String, berichtId: UUID, bijlageId: UUID): Pair<String, ByteArray> {
        // Lookup-then-route: cache is authoritative voor welke magazijn de
        // bron is. De extra round-trip is acceptabel: Redis-cache is snel en
        // het bericht-detail is hoe dan ook nodig om de bijlage-toegang te
        // autoriseren (404 op cache → 404 op uitvraag i.p.v. lekken via 502).
        val bericht = sessiecache.bericht(xOntvanger, berichtId)
        val magazijn = magazijnRouter.forMagazijn(bericht.magazijnId)

        // De Quarkus REST-client gooit zelf al een WAE bij upstream >=400 (ook
        // wanneer de signature `Response` retourneert via de default exception-
        // mapper). Vang dat hier en hermap zodat 401/403/404 1-op-1 propageren
        // en 5xx als 502 voor de client zichtbaar wordt. Een transport-storing
        // (connect/read-timeout) is een ProcessingException — die mapt naar 502,
        // net als in [BerichtBeheerService], zodat een magazijn-storing niet als
        // 500 (onze fout) maar als 502 (upstream-fout) zichtbaar wordt.
        val response = try {
            magazijn.bijlage(xOntvanger, berichtId, bijlageId)
        } catch (e: WebApplicationException) {
            throw magazijnFout(e.response?.status ?: 502)
        } catch (e: ProcessingException) {
            throw magazijnFout(502)
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

    // 401 niet via NotAuthorizedException: diens enige String-constructor vult een
    // WWW-Authenticate-challenge i.p.v. een message. Een expliciete WAE(401) geeft
    // de juiste status zonder een nepwaarde in de challenge-header.
    private fun magazijnFout(status: Int): WebApplicationException = when (status) {
        401 -> WebApplicationException("magazijn-bijlage 401", Response.Status.UNAUTHORIZED)
        403 -> ForbiddenException("magazijn-bijlage 403")
        404 -> NotFoundException("magazijn-bijlage 404")
        in 400..499 -> WebApplicationException("magazijn-bijlage 4xx ($status)", status)
        else -> WebApplicationException("magazijn-bijlage 5xx ($status)", Response.Status.BAD_GATEWAY)
    }
}
