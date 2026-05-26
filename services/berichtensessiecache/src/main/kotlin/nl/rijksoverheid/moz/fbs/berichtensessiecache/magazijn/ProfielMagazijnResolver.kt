package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.fasterxml.jackson.core.JsonProcessingException
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.profiel.PartijResponse
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Duration

@ApplicationScoped
class ProfielMagazijnResolver(
    @RestClient private val profielClient: ProfielServiceClient,
    private val clientFactory: MagazijnClientFactory,
) : MagazijnResolver {

    private val log = Logger.getLogger(ProfielMagazijnResolver::class.java)

    override fun resolve(ontvanger: Identificatienummer): Uni<Set<String>> {
        // OIN-ontvanger (B2B): geen Profiel-pad bestaat upstream; lever alle magazijnen.
        if (ontvanger.type == IdentificatienummerType.OIN) {
            return Uni.createFrom().item(clientFactory.getAllClients().keys)
        }

        val profielType = naarProfielType(ontvanger.type)

        return Uni.createFrom().item { profielClient.getPartij(profielType, ontvanger.waarde) }
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .ifNoItem().after(Duration.ofSeconds(15)).fail()
            .map { partij -> bepaalMagazijnen(partij) }
            .onFailure(io.smallrye.mutiny.TimeoutException::class.java).recoverWithUni { error ->
                Uni.createFrom().failure(
                    ProfielServiceFoutException("Profiel-service overschreed timeout", error),
                )
            }
            .onFailure(WebApplicationException::class.java).recoverWithUni { error ->
                val webEx = error as WebApplicationException

                if (webEx.response?.status == 404) {
                    log.debugf("Profiel-service 404 voor type=%s; geen voorkeuren bekend", profielType)
                    Uni.createFrom().item(emptySet<String>())
                } else {
                    Uni.createFrom().failure(
                        ProfielServiceFoutException(
                            "Profiel-service onbereikbaar (HTTP ${webEx.response?.status})",
                            error,
                        ),
                    )
                }
            }
            .onFailure(ProcessingException::class.java).recoverWithUni { error ->
                val msg = if (error.cause is JsonProcessingException) {
                    "Profiel-service onleesbaar antwoord (JSON-parse-fout)"
                } else {
                    "Profiel-service onbereikbaar (netwerkfout)"
                }
                Uni.createFrom().failure(ProfielServiceFoutException(msg, error))
            }
            // Onder andere malformed JSON komt als RuntimeException; vang restcategorie.
            .onFailure { it !is ProfielServiceFoutException }.recoverWithUni { error ->
                Uni.createFrom().failure(
                    ProfielServiceFoutException("Profiel-service onleesbaar antwoord", error),
                )
            }
    }

    private fun bepaalMagazijnen(partij: PartijResponse): Set<String> {
        val optedInOins = partij.voorkeuren
            .filter { it.voorkeurType == VOORKEUR_ONTVANG_BERICHTEN }
            .filter { it.waarde?.lowercase() in INGESCHAKELDE_WAARDEN }
            .flatMap { it.scopes }
            .mapNotNull { it.partij }
            .filter { it.identificatieType == "OIN" }
            .map { it.identificatieNummer }
            .toSet()

        if (optedInOins.isEmpty()) return emptySet()

        return clientFactory.getAlleAfzenders()
            .filter { (_, afzenders) -> afzenders.any { it.waarde in optedInOins } }
            .keys
    }

    /**
     * Mapping naar het externe profiel-contract. Expliciete `when`-vorm (geen
     * `.name`) zodat een hernoeming in de interne enum het externe contract
     * niet stilletjes breekt. OIN wordt al door de caller afgevangen voordat
     * deze functie geraakt wordt.
     */
    private fun naarProfielType(type: IdentificatienummerType): String = when (type) {
        IdentificatienummerType.BSN -> "BSN"
        IdentificatienummerType.RSIN -> "RSIN"
        IdentificatienummerType.KVK -> "KVK"
        IdentificatienummerType.OIN -> error("OIN-ontvanger moet vóór Profiel-call afgevangen worden")
    }

    companion object {
        private const val VOORKEUR_ONTVANG_BERICHTEN = "OntvangViaBerichtenbox"
        private val INGESCHAKELDE_WAARDEN = setOf("true", "ja")
    }
}
