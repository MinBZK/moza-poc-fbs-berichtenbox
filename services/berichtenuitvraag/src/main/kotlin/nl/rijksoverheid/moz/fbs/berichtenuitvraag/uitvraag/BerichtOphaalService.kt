package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Bericht-detail uit de sessiecache; bijlagen rechtstreeks uit het magazijn
 * (passthrough). `haalBijlage` levert `(mimeType, bytes)`: de resource legt het
 * mimeType op een request-property zodat [BijlageContentTypeFilter] de
 * response-`Content-Type` kan overrulen.
 *
 * Bytes worden volledig in een ByteArray geladen; het geheugengebruik per request
 * is begrensd door de bijlage-limiet die het magazijn afdwingt
 * (`Bijlage.MAX_CONTENT_BYTES`) — geen waarde hier dupliceren, die leeft in het magazijn.
 *
 * Magazijn-fouten worden status-behoudend doorgegeven: elke echte 4xx propageert
 * status-behoudend (401/403/404 expliciet, overige 4xx generiek) zodat de OpenAPI-
 * belofte van 404 en de LDV-audittrail kloppen; 5xx en transport-fouten mappen naar
 * 502 (downstream-faal). Een 2xx-respons zónder Content-Type-header is eveneens een
 * echte upstream-bug en mapt naar 502.
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
        mapUpstreamFout(log, "cache-bericht-lookup (berichtId=$berichtId)") {
            sessiecache.bericht(xOntvanger, berichtId)
        }

    fun haalBijlage(xOntvanger: String, berichtId: UUID, bijlageId: UUID): Pair<String, ByteArray> {
        // Lookup-then-route: cache is authoritative voor welke magazijn de
        // bron is. De extra round-trip is acceptabel: Redis-cache is snel en
        // het bericht-detail is hoe dan ook nodig om de bijlage-toegang te
        // autoriseren (404 op cache → 404 op uitvraag i.p.v. lekken via 502).
        val bericht = mapUpstreamFout(log, "cache-bericht-lookup vóór bijlage (berichtId=$berichtId)") {
            sessiecache.bericht(xOntvanger, berichtId)
        }
        val magazijn = magazijnRouter.forMagazijn(bericht.magazijnId)

        // `bijlage` heeft een `Response`-returntype: dán past de Quarkus REST-client
        // géén default exception-mapper toe en geeft elke upstream-status (ook >=400)
        // rauw terug. De statusgebaseerde mapping gebeurt daarom hieronder op
        // `response.status` (de `>= 400`-tak), níet hier. Dit try/catch dekt alleen de
        // randgevallen waarin de client tóch gooit: een transport-storing vóór het
        // HTTP-antwoord komt als ProcessingException, en een proxy-/client-laag kan een
        // WebApplicationException gooien. In beide gevallen sluiten we de eventuele
        // upstream-response (connectie-lek) en mappen 4xx status-behoudend / rest → 502.
        val response = try {
            magazijn.bijlage(xOntvanger, berichtId, bijlageId)
        } catch (e: WebApplicationException) {
            val status = e.response?.status

            // Sluit de upstream-response: een WAE van de REST-client kan een open
            // verbinding/stream vasthouden; niet sluiten lekt connecties uit de pool
            // bij fout-traffic. Een falende close mag de re-throw van de echte fout niet
            // overschaduwen — vandaar runCatching — maar wordt op debug gelogd zodat een
            // pool-lek bij diagnose (dev/test, of prod met verhoogd logniveau) zichtbaar is.
            runCatching { e.response?.close() }
                .onFailure { log.debugf(it, "kon upstream-response niet sluiten na magazijn-bijlage-WAE") }

            // Allowlist (consistent met isUpstreamTransportFout in [UpstreamFault]):
            // alleen een echte 4xx propageert status-behoudend; geen response
            // (transport-fout) én elke non-4xx-status (3xx/5xx/onverwacht) → 502.
            // Altijd loggen vóór re-throw.
            if (status == null || status !in 400..499) {
                log.errorf(e, "magazijn-bijlage upstream-fout (status=%s) → 502", status?.toString() ?: "geen response")

                throw magazijnFout(502)
            }

            throw magazijnFout(status)
        } catch (e: ProcessingException) {
            log.errorf(e, "magazijn-bijlage transport-fout → 502")

            throw magazijnFout(502)
        }

        try {
            // Primair mappingpad: bij een `Response`-returntype geeft de REST-client de
            // upstream-status rauw terug (gooit niet), dus elke 4xx/5xx komt hier langs.
            // magazijnFout propageert 4xx status-behoudend en mapt 5xx/onverwacht → 502.
            if (response.status >= 400) throw magazijnFout(response.status)

            // Pak de raw Content-Type-header (niet `response.mediaType`, die
            // parsed naar `MediaType?` en geeft null bij een ongeldig MIME-type;
            // de fail-closed-fallback in [BijlageContentTypeFilter] hoort die
            // case af te handelen). Volledig ontbrekende header is hier wel
            // een echte upstream-bug → 502.
            val mimeType = response.getHeaderString("Content-Type")
                ?: throw WebApplicationException("magazijn-bijlage zonder Content-Type", Response.Status.BAD_GATEWAY)

            // De body-read kan zelf falen (bv. ProcessingException op een afgekapte/
            // corrupte stream). Dat is een upstream-storing, niet onze fout → 502;
            // zonder deze wrap zou het als 500 naar de UncaughtExceptionMapper lekken.
            val bytes = try {
                response.readEntity(ByteArray::class.java)
            } catch (e: ProcessingException) {
                log.errorf(e, "magazijn-bijlage body-read mislukt (afgekapte/corrupte stream) → 502")

                throw magazijnFout(502)
            }

            return mimeType to bytes
        } finally {
            // Een falende close mag de echte fout niet maskeren (vandaar runCatching),
            // maar op warn: een aanhoudend faaltje hier betekent een lekkende connectie-
            // pool en moet in prod zichtbaar zijn, niet onder debug verdwijnen.
            runCatching { response.close() }
                .onFailure { log.warnf(it, "kon upstream-response niet sluiten na magazijn-bijlage-read (mogelijk connectie-pool-lek)") }
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

    private companion object {
        private val log: Logger = Logger.getLogger(BerichtOphaalService::class.java)
    }
}
