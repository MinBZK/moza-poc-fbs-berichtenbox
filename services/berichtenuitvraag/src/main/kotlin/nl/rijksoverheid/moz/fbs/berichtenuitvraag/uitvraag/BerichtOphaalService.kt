package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Bericht-detail uit de in-process [Sessiecache]-facade; bijlagen als
 * passthrough uit het magazijn. `haalBijlage` levert `(mimeType, bytes)`: de
 * resource zet het mimeType op een request-property zodat
 * [BijlageContentTypeFilter] de response-`Content-Type` overrult.
 *
 * Bijlage-bytes worden volledig in een ByteArray geladen; de magazijn-limiet
 * (`Bijlage.MAX_CONTENT_BYTES`) begrenst het geheugen per request.
 * TODO(#572): vervang door StreamingOutput-passthrough zodra de werkelijke grootte-
 * verdeling het dubbel-bufferen (client-read + JAX-RS-serialize) rechtvaardigt.
 *
 * Magazijn-fouten zijn status-behoudend: echte 4xx propageert (401/403/404 expliciet,
 * overige generiek) zodat OpenAPI-404 en LDV-audittrail kloppen; 5xx, transport-fouten
 * en een 2xx zónder Content-Type → 502 (downstream-faal).
 *
 * Routering: het bericht-detail (uit de cache, niet een client-id — anders kon een
 * aanvaller bijlages uit een vreemd magazijn opvragen) levert het bron-`magazijnId`
 * waarmee [MagazijnRouter] de juiste magazijn-URL voor de bytes kiest.
 */
@ApplicationScoped
class BerichtOphaalService(
    private val sessiecache: Sessiecache,
    private val magazijnRouter: MagazijnRouter,
) {
    fun haalBericht(xOntvanger: String, berichtId: UUID): Bericht {
        val domeinBericht = zoekBerichtInCache(xOntvanger, berichtId)
            ?: throw NotFoundException("Bericht niet gevonden")

        return UitvraagDtoMapper.toApiBericht(domeinBericht)
    }

    fun haalBijlage(xOntvanger: String, berichtId: UUID, bijlageId: UUID): Pair<String, ByteArray> {
        // Lookup-then-route: cache is authoritative voor welk magazijn de
        // bron is. De extra cache-read is acceptabel: Redis is snel en
        // het bericht-detail is hoe dan ook nodig om de bijlage-toegang te
        // autoriseren (404 op cache → 404 op uitvraag i.p.v. lekken via 502).
        val bericht = zoekBerichtInCache(xOntvanger, berichtId)
            ?: throw NotFoundException("Bericht niet gevonden")
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

            // Allowlist (consistent met isUpstreamStoring in [UpstreamFault]):
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

    private fun zoekBerichtInCache(xOntvanger: String, berichtId: UUID) =
        leesUitCache(log, "cache-bericht-lookup (berichtId=$berichtId)") {
            sessiecache.bericht(Identificatienummer.fromHeader(xOntvanger), berichtId)
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
