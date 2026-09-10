package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.fasterxml.jackson.core.JsonProcessingException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.common.exception.FbsFoutException
import nl.rijksoverheid.moz.fbs.common.exception.Foutcode
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Bericht-detail uit de in-process [Sessiecache]-facade, aangevuld met de berichttekst
 * uit het bronmagazijn; bijlagen als passthrough uit het magazijn. `haalBijlage` levert
 * [BijlageInhoud]: de resource zet mimeType en bestandsnaam op request-properties zodat
 * [BijlageContentTypeFilter] de response-`Content-Type` overrult en de
 * `Content-Disposition` bouwt.
 *
 * Bijlage-bytes worden volledig in een ByteArray geladen; de magazijn-limiet
 * (`Bijlage.MAX_CONTENT_BYTES`) begrenst het geheugen per request.
 * TODO(#572): vervang door StreamingOutput-passthrough zodra de werkelijke grootte-
 * verdeling het dubbel-bufferen (client-read + JAX-RS-serialize) rechtvaardigt.
 *
 * Magazijn-fouten zijn op beide paden status-behoudend: een echte 4xx propageert zodat
 * OpenAPI-404 en LDV-audittrail kloppen, 5xx en transport-fouten worden 502. De twee paden
 * bereiken dat langs verschillende weg: de tekst-call via [mapUpstreamFout], de bijlage-call
 * met een eigen mapping op `response.status` — een `Response`-returntype schakelt de
 * exception-mapper van de client uit, en levert daarbij de extra tak "2xx zónder
 * Content-Type → 502".
 *
 * Routering: het bericht-detail (uit de cache, niet een client-id — anders kon een
 * aanvaller tekst of bijlages uit een vreemd magazijn opvragen) levert het bron-`magazijnId`
 * waarmee [MagazijnRouter] de juiste magazijn-URL kiest, zowel voor de berichttekst als
 * voor de bijlage-bytes.
 */
@ApplicationScoped
class BerichtOphaalService(
    private val sessiecache: Sessiecache,
    private val magazijnRouter: MagazijnRouter,
    private val afzendernamen: Afzendernamen,
) {
    fun haalBericht(xOntvanger: String, berichtId: UUID): Bericht {
        val domeinBericht = zoekBerichtInCache(xOntvanger, berichtId)
            ?: throw berichtOnbekend()

        return UitvraagDtoMapper.toApiBericht(
            domeinBericht,
            afzenderNaam = afzendernamen.naamVoor(domeinBericht),
            inhoud = haalInhoud(xOntvanger, berichtId, domeinBericht.magazijnId),
        )
    }

    /**
     * De berichttekst staat niet in de cache: die blijft bij de bron tot de ontvanger het
     * bericht opent. Routeren op het `magazijnId` uit het gecachete bericht — niet op iets
     * uit het verzoek — zodat een aanroeper geen tekst uit een vreemd magazijn kan opvragen.
     *
     * Gevolg van het niet-vooruit-kopiëren: bij een onbereikbaar bronmagazijn is dit ene
     * bericht niet te openen, terwijl de lijst gewoon zichtbaar blijft.
     *
     * Een 404 laat de cache-entry bewust staan. Zelfherstel zou een schrijfactie op een
     * leespad zijn en zou een routeringsfout maskeren als "bericht bestaat niet meer"; de
     * TTL ruimt de entry op. De logregel hieronder is wat het onderscheid mogelijk maakt.
     */
    private fun haalInhoud(xOntvanger: String, berichtId: UUID, magazijnId: String): String {
        val magazijn = magazijnRouter.forMagazijn(magazijnId)

        val detail = try {
            mapUpstreamFout(log, MAGAZIJN_DETAIL) {
                magazijn.bericht(xOntvanger, berichtId)
            }
        } catch (e: WebApplicationException) {
            // mapUpstreamFout logt zijn eigen 502-gevallen; een propagerende 4xx ging tot nu toe
            // ongelogd de deur uit. Sinds het openen van een bericht een magazijn-aanroep doet is
            // dat de faalklasse die élke klik raakt: een verkeerde grant of een ontvanger-mismatch
            // maakt elk bericht onopenbaar zonder dat hier iets van te zien is. Geen X-Ontvanger
            // in de log — dat is een BSN/RSIN.
            if (!isUpstreamStoring(e)) {
                log.warnf(
                    "%s: magazijn gaf %d (berichtId=%s magazijnId=%s)",
                    MAGAZIJN_DETAIL,
                    e.response.status,
                    berichtId,
                    magazijnId,
                )
            }

            throw e
        } catch (e: JsonProcessingException) {
            // Een 200 met een body die niet op het contract past. Zonder deze tak landt hij op de
            // Jackson-mapper, die er een 400 van maakt: de gebruiker leest dan dat zíjn verzoek
            // fout is terwijl de fout volledig bovenstrooms zit.
            log.errorf(e, "%s: onleesbaar antwoord (schema-drift?) berichtId=%s magazijnId=%s → 502", MAGAZIJN_DETAIL, berichtId, magazijnId)

            throw upstreamBadGateway("$MAGAZIJN_DETAIL: onleesbaar antwoord", e)
        }

        // Het contract eist een niet-lege tekst. Levert een magazijn er tóch een, dan is een leeg
        // scherm niet van een leeg bericht te onderscheiden; luid falen is hier het bruikbare
        // signaal, voor de gebruiker en voor de beheerder van dat magazijn.
        if (detail.inhoud.isBlank()) {
            log.errorf("%s: magazijn leverde lege berichttekst (berichtId=%s magazijnId=%s) → 502", MAGAZIJN_DETAIL, berichtId, magazijnId)

            throw upstreamBadGateway("$MAGAZIJN_DETAIL: lege berichttekst")
        }

        return detail.inhoud
    }

    fun haalBijlage(xOntvanger: String, berichtId: UUID, bijlageId: UUID): BijlageInhoud {
        // Lookup-then-route: cache is authoritative voor welk magazijn de
        // bron is. De extra cache-read is acceptabel: Redis is snel en
        // het bericht-detail is hoe dan ook nodig om de bijlage-toegang te
        // autoriseren (een misser op de cache wordt hier de 404 of 410 van de
        // client, i.p.v. te lekken via een 502).
        val bericht = zoekBerichtInCache(xOntvanger, berichtId)
            ?: throw berichtOnbekend()
        val magazijn = magazijnRouter.forMagazijn(bericht.magazijnId)

        // De naam komt uit hetzelfde bericht-detail, niet uit de `Content-Disposition`
        // van het magazijn: die is daar al gesaneerd en gecodeerd, en terugparsen zou
        // dat moeten omkeren. Kent de cache de bijlage niet, dan gaan de bytes zonder
        // naam de deur uit — of ze bestaan, blijft het oordeel van het magazijn.
        val bestandsnaam = bericht.bijlagen.firstOrNull { it.bijlageId == bijlageId }?.naam

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
                ?: throw upstreamBadGateway("magazijn-bijlage zonder Content-Type")

            // De body-read kan zelf falen (bv. ProcessingException op een afgekapte/
            // corrupte stream). Dat is een upstream-storing, niet onze fout → 502;
            // zonder deze wrap zou het als 500 naar de UncaughtExceptionMapper lekken.
            val bytes = try {
                response.readEntity(ByteArray::class.java)
            } catch (e: ProcessingException) {
                log.errorf(e, "magazijn-bijlage body-read mislukt (afgekapte/corrupte stream) → 502")

                throw magazijnFout(502)
            }

            return BijlageInhoud(mimeType, bestandsnaam, bytes)
        } finally {
            // Een falende close mag de echte fout niet maskeren (vandaar runCatching),
            // maar op warn: een aanhoudend faaltje hier betekent een lekkende connectie-
            // pool en moet in prod zichtbaar zijn, niet onder debug verdwijnen.
            runCatching { response.close() }
                .onFailure { log.warnf(it, "kon upstream-response niet sluiten na magazijn-bijlage-read (mogelijk connectie-pool-lek)") }
        }
    }

    /**
     * Wat er nodig is om een bijlage uit te leveren. `inhoud` wordt niet defensief
     * gekopieerd — aanroepers mogen de bytes niet muteren; een kopie per bijlage zou
     * de heap-druk verdubbelen.
     *
     * Bewust geen `data class`: er wordt nergens vergeleken of gedestructureerd, en de
     * gegenereerde `toString` zou de bestandsnaam — die persoonsgegevens kan bevatten —
     * in elke logregel zetten waarin het object per ongeluk belandt.
     */
    class BijlageInhoud(val mimeType: String, val bestandsnaam: String?, val inhoud: ByteArray)

    private fun zoekBerichtInCache(xOntvanger: String, berichtId: UUID) =
        leesUitCache(log, "cache-bericht-lookup (berichtId=$berichtId)") {
            sessiecache.bericht(Identificatienummer.fromHeader(xOntvanger), berichtId)
        }

    private fun berichtOnbekend() =
        FbsFoutException(Foutcode.BERICHT_ONBEKEND, Response.Status.NOT_FOUND, "Bericht niet gevonden")

    // 401 niet via NotAuthorizedException: diens enige String-constructor vult een
    // WWW-Authenticate-challenge i.p.v. een message. Een expliciete fout met 401-status geeft
    // de juiste status zonder een nepwaarde in de challenge-header.
    private fun magazijnFout(status: Int): WebApplicationException = when (status) {
        401 -> FbsFoutException(Foutcode.GEEN_TOEGANG, Response.Status.UNAUTHORIZED, "magazijn-bijlage 401")
        403 -> FbsFoutException(Foutcode.GEEN_TOEGANG, Response.Status.FORBIDDEN, "magazijn-bijlage 403")
        // Het magazijn filtert een verwijderd bericht al in zijn query weg, vóór de eigenaar-check.
        // Een eigen kenmerk voor "verwijderd" zou hier dus het bestaan van andermans bericht
        // verraden; een magazijn-404 op de bytes blijft daarom onbekend. Het onderscheid dat de
        // client wél krijgt komt uit de cache-lookup hierboven, die per ontvanger gesleuteld is.
        404 -> berichtOnbekend()
        // Ook een upstream-410: die kan alleen van een tussenliggende schakel komen (het magazijn
        // geeft hem op dit pad niet) en zou via de statusterugval anders alsnog als "verwijderd"
        // bij de client landen.
        410 -> berichtOnbekend()
        in 400..499 -> WebApplicationException("magazijn-bijlage 4xx ($status)", status)
        else -> upstreamBadGateway("magazijn-bijlage 5xx ($status)")
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BerichtOphaalService::class.java)

        /** Log-context van de tekst-call; één waarde zodat de regels bij elkaar te zoeken zijn. */
        private const val MAGAZIJN_DETAIL = "magazijn-bericht-detail"
    }
}
