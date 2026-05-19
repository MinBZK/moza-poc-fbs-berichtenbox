package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.NieuweBijlage
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * CDI bean voor toepasselijke validatie vóór opslag (issue #541).
 *
 * Twee verantwoordelijkheden:
 *  1. **Technische validatie van bijlagen** — alleen `application/pdf` is toegestaan.
 *     Lengte/grootte/inhoud van bijlagen wordt al door [NieuweBijlage]/`Bijlage` zelf
 *     bewaakt (domein-invarianten); deze service voegt de PoC-policy "PDF-only" toe.
 *  2. **Toestemmingscontrole** via de Profiel Service voor zowel burgers (BSN) als
 *     ondernemers (RSIN, KvK). Voor `OIN`-ontvangers (organisatie-naar-organisatie)
 *     is toestemming niet van toepassing — de policy gaat over individuele consent
 *     en die kennen wij van rechtspersoon-tot-rechtspersoon-correspondentie niet.
 *
 * Volgorde van checks: MIME-type eerst (lokaal, gratis), pas daarna de externe
 * toestemmings-call. Zo voorkomen we een netwerk-call voor een bericht dat sowieso
 * geweigerd wordt op een goedkopere check.
 */
@ApplicationScoped
class BerichtValidatieService(
    @RestClient private val toestemmingControle: ToestemmingControle,
) {

    fun valideer(bericht: Bericht, bijlagen: List<NieuweBijlage>) {
        bijlagen.forEach { bijlage ->
            if (bijlage.mimeType != PDF_MIME_TYPE) {
                throw DomainValidationException(
                    "Bijlage mimeType moet $PDF_MIME_TYPE zijn (was: ${bijlage.mimeType})",
                )
            }
        }

        controleerToestemming(bericht)
    }

    private fun controleerToestemming(bericht: Bericht) {
        val ontvangerType = when (bericht.ontvanger.type) {
            IdentificatienummerType.BSN -> "BSN"
            IdentificatienummerType.RSIN -> "RSIN"
            IdentificatienummerType.KVK -> "KVK"
            // Organisatie-naar-organisatie: geen consent-model in PoC, geen REST-call.
            IdentificatienummerType.OIN -> return
        }

        val antwoord = toestemmingControle.controleer(
            ToestemmingVerzoek(
                ontvangerType = ontvangerType,
                ontvangerWaarde = bericht.ontvanger.waarde,
                afzender = bericht.afzender.waarde,
            ),
        )

        if (!antwoord.toegestaan) {
            // Bewust geen ontvangerWaarde in de boodschap: kan PII zijn (BSN).
            throw ToestemmingGeweigerdException(
                "Toestemming ontvanger ontbreekt voor afzender ${bericht.afzender.waarde}",
            )
        }
    }

    companion object {
        private const val PDF_MIME_TYPE = "application/pdf"
    }
}
