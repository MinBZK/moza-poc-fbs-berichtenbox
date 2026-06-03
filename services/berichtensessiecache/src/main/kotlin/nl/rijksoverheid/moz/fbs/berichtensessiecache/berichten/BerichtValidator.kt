package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import jakarta.enterprise.context.ApplicationScoped

/**
 * Valideert de defensieve grenzen ([BerichtLimieten]) op een [Bericht] vlak na constructie
 * (in cache-write- en magazijn-mapping-paden). Bewust gescheiden van [Bericht.init]: de
 * limieten zijn omgevings-configureerbaar; ze in het data class plaatsen zou een CDI-lookup
 * in een waardetype dwingen.
 *
 * Gooit [IllegalArgumentException] bij overschrijding zodat de bestaande exception-mappers
 * (`ConstraintViolationExceptionMapper`/`DomainValidationExceptionMapper`) de fout consistent
 * vertalen naar 400 Problem+JSON.
 */
@ApplicationScoped
class BerichtValidator(
    private val limieten: BerichtLimieten,
) {
    fun valideer(bericht: Bericht): Bericht {
        require(bericht.bijlagen.size <= limieten.maxBijlagen()) {
            "Maximaal ${limieten.maxBijlagen()} bijlagen per bericht"
        }

        bericht.bijlagen.forEach { bijlage ->
            require(bijlage.naam.length <= limieten.bijlageNaamMaxLengte()) {
                "bijlage-naam mag maximaal ${limieten.bijlageNaamMaxLengte()} tekens zijn"
            }
        }

        return bericht
    }
}
