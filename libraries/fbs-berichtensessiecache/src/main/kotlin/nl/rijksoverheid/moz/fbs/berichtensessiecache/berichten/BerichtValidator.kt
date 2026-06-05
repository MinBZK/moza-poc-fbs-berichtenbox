package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Valideert de defensieve grenzen ([BerichtLimieten]) op een [Bericht] vlak na constructie
 * (in cache-write- en magazijn-mapping-paden). Bewust gescheiden van [Bericht.init]: de
 * limieten zijn omgevings-configureerbaar; ze in het data class plaatsen zou een CDI-lookup
 * in een waardetype dwingen.
 *
 * Twee aanroep-varianten omdat het call-site-gedrag verschilt:
 * - [valideer] gooit (POST /berichten): één invalid bericht is een client-fout op één request,
 *   dus laten we hem als 400 propageren via de exception-mappers.
 * - [valideerOrLogAndDrop] retourneert null (magazijn-aggregatie): één invalid bericht in een
 *   batch van honderden mag de hele ophaal-flow niet kapot maken; we slaan hem over en loggen.
 */
@ApplicationScoped
internal class BerichtValidator(
    private val limieten: BerichtLimieten,
) {
    private val log = Logger.getLogger(BerichtValidator::class.java)

    fun valideer(bericht: Bericht): Bericht {
        require(bericht.bijlagen.size <= limieten.maxBijlagen()) {
            "Maximaal ${limieten.maxBijlagen()} bijlagen per bericht"
        }

        bericht.bijlagen.forEach { bijlage ->
            require(bijlage.naam.length <= limieten.maxBijlageNaamLengte()) {
                "bijlage-naam mag maximaal ${limieten.maxBijlageNaamLengte()} tekens zijn"
            }
        }

        return bericht
    }

    fun valideerOrLogAndDrop(bericht: Bericht): Bericht? =
        runCatching { valideer(bericht) }
            .onFailure { e ->
                // warnf: defensieve grens overschreden door upstream-magazijn-data. Geen errorf
                // omdat we hier geen alertbaar applicatie-defect zien; de boodschap is "magazijn-X
                // levert pathologische data voor berichtId=Y". berichtId is geen PII; verdere
                // bericht-velden NIET loggen (afzender/ontvanger zijn PII).
                log.warnf(
                    "Bericht overgeslagen tijdens magazijn-aggregatie (limietsoverschrijding): berichtId=%s magazijnId=%s reden=%s",
                    bericht.berichtId,
                    bericht.magazijnId,
                    e.message,
                )
            }
            .getOrNull()
}
