package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.BijlageInvoer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.profiel.PartijRequest
import nl.rijksoverheid.moz.fbs.common.profiel.Profiel404Duiding
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielNietGevonden
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielVoorkeuren
import nl.rijksoverheid.moz.fbs.common.profiel.ToestemmingGeweigerdException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/**
 * CDI bean voor toepasselijke validatie vóór opslag (issue #541).
 *
 * Twee verantwoordelijkheden:
 *  1. **Technische validatie van bijlagen** — alleen `application/pdf` is toegestaan.
 *  2. **Abonnementscontrole** via de MOZA Profiel Service. Voor BSN/RSIN/KVK-
 *     ontvangers halen we het profiel op en checken of er een voorkeur
 *     `OntvangViaBerichtenbox` bestaat met een scope naar de afzender-OIN.
 *     Voor OIN-ontvangers (organisatie-naar-organisatie) is dit niet van toepassing.
 *
 * Volgorde: MIME-typen eerst (lokaal, gratis), pas daarna de externe call. Bij een
 * 404 van de profiel-service: de ontvanger heeft geen profiel → geen toestemming
 * (fail-closed). Andere HTTP-fouten propageren.
 *
 * Deze service draait binnen `BerichtOpslagService.valideerAanlevering`, dat een eigen
 * circuit breaker heeft. Wat daar meetelt is een transportstoring
 * (`ProcessingException`: connection refused/reset/read-timeout); een
 * `ToestemmingGeweigerdException` en elk HTTP-antwoord van de profiel-service staan in
 * `skipOn` en openen het circuit dus niet.
 */
@ApplicationScoped
class BerichtValidatieService(
    @RestClient private val profielServiceClient: ProfielServiceClient,
) {

    private val log = Logger.getLogger(BerichtValidatieService::class.java)

    fun valideer(bericht: Bericht, bijlagen: List<BijlageInvoer>) {
        bijlagen.forEach { bijlage ->
            if (bijlage.mimeType != PDF_MIME_TYPE) {
                throw DomainValidationException(
                    "Bijlage mimeType moet $PDF_MIME_TYPE zijn (was: ${bijlage.mimeType})",
                )
            }
        }

        controleerAbonnement(bericht)
    }

    private fun controleerAbonnement(bericht: Bericht) {
        // Organisatie-naar-organisatie valt buiten het profiel-service-model.
        if (bericht.ontvanger.type == IdentificatienummerType.OIN) return

        val aanvraag = PartijRequest.van(bericht.ontvanger)
        val ontvangerType = aanvraag.identificatieType

        val partij = try {
            profielServiceClient.getPartij(aanvraag)
        } catch (ex: WebApplicationException) {
            // Quarkus REST Reactive werpt `ClientWebApplicationException` voor élke
            // 4xx — niet de typespecifieke `NotFoundException`. We filteren expliciet
            // op statuscode 404 en behandelen dat als fail-closed; elke andere status
            // (400 op een geweigerde request-body, 401/403 op auth-misser, 5xx) propageert wél, zodat de
            // aanleveraar een fout ziet in plaats van een stille afwijzing. Ze tellen
            // niet mee voor het circuit: een HTTP-antwoord betekent dat de upstream
            // leeft. Netwerk-fouten zijn geen `WebApplicationException`, passeren deze
            // catch en openen het circuit wél.
            //
            // PII-veilig: de doorgegooide WAE belandt bij ProblemExceptionMapper, die de
            // 4xx-detail saneert. Het identificatienummer zit in de request-body en niet in
            // de upstream-URL, dus ook een ongesaneerde message zou het niet dragen.
            if (ex.response?.status != 404) throw ex

            // Een 404 betekent twee dingen: "deze ontvanger heeft nog geen profiel" en "de
            // koppeling naar de Profiel-service deugt niet". Beide leiden hier tot een
            // afgewezen aanlevering, maar de aanleveraar moet weten wélke: een weigering is
            // definitief en wordt niet opnieuw geprobeerd, een storing wél.
            val duiding = ProfielNietGevonden.duidRespons(ex.response)

            if (duiding is Profiel404Duiding.Storing) {
                // Errorf, niet warnf: dit is geen policy-besluit maar een defect. Alleen de
                // duiding de log in, nooit de rauwe body — een upstream mag daar het
                // identificatienummer in echoën.
                log.errorf(
                    ex,
                    "Profiel-service 404 die geen 'partij niet gevonden' is voor ontvangerType=%s afzender=%s (%s) — behandeld als storing",
                    ontvangerType,
                    bericht.afzender.waarde,
                    duiding.omschrijving,
                )
                throw ProfielServiceFoutException.upstreamError(404, ex)
            }

            // Onbekende ontvanger → fail-closed: behandel als geen toestemming.
            // Log op WARN: een aanleveraar die stelselmatig ontvangers zonder profiel
            // aanbiedt is zichtbaar, zonder dat het als storing alarmeert. De
            // ontvanger-waarde blijft uit de log om geen BSN/RSIN te lekken.
            log.warnf(
                "Profiel-service meldt geen profiel voor ontvangerType=%s afzender=%s — fail-closed (geen toestemming)",
                ontvangerType,
                bericht.afzender.waarde,
            )
            throw ToestemmingGeweigerdException.geenProfiel()
        }

        if (!ProfielVoorkeuren.isOptedInVoorAfzender(partij, bericht.afzender)) {
            // afzender-OIN in de log (geen PII van burger; organisatie-identificatie) zodat
            // ops kan diagnosticeren welke combinatie geweigerd werd. Body lekt afzender niet —
            // de factory hardcodeert de message.
            log.infof(
                "Toestemming geweigerd: geen actieve voorkeur voor afzender=%s",
                bericht.afzender.waarde,
            )
            throw ToestemmingGeweigerdException.geenActieveVoorkeur()
        }
    }

    companion object {
        private const val PDF_MIME_TYPE = "application/pdf"
    }
}
