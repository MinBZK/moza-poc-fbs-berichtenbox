package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.BijlageInvoer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
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
 * (fail-closed). Andere HTTP-fouten propageren — `ToestemmingGeweigerdException`
 * staat in `skipOn` zodat de circuit breaker daar niet door opent, maar échte
 * infrastructuurfouten openen 'm wél.
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
        val ontvangerType = when (bericht.ontvanger.type) {
            IdentificatienummerType.BSN -> "BSN"
            IdentificatienummerType.RSIN -> "RSIN"
            IdentificatienummerType.KVK -> "KVK"
            // Organisatie-naar-organisatie valt buiten het profiel-service-model.
            IdentificatienummerType.OIN -> return
        }

        val partij = try {
            profielServiceClient.getPartij(ontvangerType, bericht.ontvanger.waarde)
        } catch (ex: WebApplicationException) {
            // Quarkus REST Reactive werpt `ClientWebApplicationException` voor élke
            // 4xx — niet de typespecifieke `NotFoundException`. We filteren expliciet
            // op statuscode 404 en behandelen dat als fail-closed; andere 4xx (400
            // op invalide path, 401/403 op auth-misser) propageren wél zodat het
            // circuit breaker ze meetelt. 5xx en netwerk-fouten zijn geen
            // `WebApplicationException` en passeren deze catch sowieso.
            if (ex.response?.status != 404) throw ex
            // Onbekende ontvanger → fail-closed: behandel als geen toestemming.
            // Log op WARN zodat een configuratiefout (verkeerd base-path → 404 op
            // élke ontvanger) zichtbaar wordt; de ontvanger-waarde blijft uit de
            // log om geen BSN/RSIN te lekken.
            log.warnf(
                "Profiel-service 404 voor ontvangerType=%s afzender=%s — fail-closed (geen toestemming)",
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
