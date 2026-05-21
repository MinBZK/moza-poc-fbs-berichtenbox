package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.BijlageInvoer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
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
 * (fail-closed). Andere HTTP-fouten propageren — daar trippt het circuit breaker
 * niet op want `ToestemmingGeweigerdException` staat in `skipOn`, maar échte
 * infrastructuurfouten doen dat wél.
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
            throw ToestemmingGeweigerdException(
                "Ontvanger heeft geen profiel bij MOZA voor afzender ${bericht.afzender.waarde}",
            )
        }

        if (!isAbonneeOp(partij, bericht.afzender)) {
            // Bewust geen ontvanger-waarde in de boodschap: kan PII zijn (BSN).
            throw ToestemmingGeweigerdException(
                "Ontvanger heeft geen actieve berichtenbox-voorkeur voor afzender ${bericht.afzender.waarde}",
            )
        }
    }

    /**
     * True als de partij een `OntvangViaBerichtenbox`-voorkeur heeft met
     * `waarde` in ['true', 'ja'] (case-insensitive) én een scope waarvan de
     * partij een OIN-identificatie heeft die gelijk is aan de afzender.
     *
     * Dienst-id binnen de scope negeren we voor nu — alle diensten van de
     * afzender vallen onder dezelfde toestemming voor deze PoC.
     */
    private fun isAbonneeOp(partij: PartijResponse, afzender: Oin): Boolean =
        partij.voorkeuren.any { voorkeur ->
            voorkeur.voorkeurType == VOORKEUR_ONTVANG_BERICHTEN &&
                voorkeur.waarde?.lowercase() in INGESCHAKELDE_WAARDEN &&
                voorkeur.scopes.any { scope ->
                    scope.partij?.identificatieType == "OIN" &&
                        scope.partij.identificatieNummer == afzender.waarde
                }
        }

    companion object {
        private const val PDF_MIME_TYPE = "application/pdf"
        private const val VOORKEUR_ONTVANG_BERICHTEN = "OntvangViaBerichtenbox"

        // Profiel-service modelleert booleane voorkeuren als string. We accepteren
        // beide Nederlandstalige en Engelstalige opt-in-waarden; alles anders
        // (null, "false", "nee", lege string) telt als opt-out.
        private val INGESCHAKELDE_WAARDEN = setOf("true", "ja")
    }
}
