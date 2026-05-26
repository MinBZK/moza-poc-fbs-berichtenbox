package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.fasterxml.jackson.core.JsonProcessingException
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
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

        // Inner-timeout-budget = retry-budget van de REST-client + marge:
        //   3 pogingen × read-timeout 5s + 2 × delay 200ms = ~15.4s. Op 15s zou de
        //   Mutiny-timeout vóór de laatste retry kunnen afslaan; op 18s heeft elke
        //   retry zijn beurt en blijft er nog ~2s marge onder de caller-await (25s).
        return Uni.createFrom().item { profielClient.getPartij(profielType, ontvanger.waarde) }
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .ifNoItem().after(Duration.ofSeconds(18)).fail()
            .map { partij -> bepaalMagazijnen(partij) }
            .onFailure(io.smallrye.mutiny.TimeoutException::class.java).recoverWithUni { error ->
                Uni.createFrom().failure(ProfielServiceFoutException.timeout(error))
            }
            .onFailure(WebApplicationException::class.java).recoverWithUni { error ->
                val webEx = error as WebApplicationException

                if (webEx.response?.status == 404) {
                    log.debugf("Profiel-service 404 voor type=%s; geen voorkeuren bekend", profielType)
                    Uni.createFrom().item(emptySet<String>())
                } else {
                    Uni.createFrom().failure(
                        ProfielServiceFoutException.upstreamError(webEx.response?.status ?: 0, error),
                    )
                }
            }
            .onFailure(ProcessingException::class.java).recoverWithUni { error ->
                val ex = if (error.cause is JsonProcessingException) {
                    ProfielServiceFoutException.malformed(error)
                } else {
                    ProfielServiceFoutException.netwerk(error)
                }
                Uni.createFrom().failure(ex)
            }
            // Catch-all voor onverwachte RuntimeExceptions die niet eerder zijn afgevangen
            // (bv. NullPointerException uit de gegenereerde client of een interne fout in
            // bepaalMagazijnen). Wrap als ProfielServiceFoutException zodat de caller
            // consistent 503 + Retry-After krijgt in plaats van een onverwachte 500.
            // Expliciet errorf-loggen vóór de wrap: zonder dit verbergt het 503-pad een
            // eigen-code-bug als upstream-fout en gaat de bug in productie ongezien.
            .onFailure { it !is ProfielServiceFoutException }.recoverWithUni { error ->
                log.errorf(
                    error,
                    "Onverwachte fout in Profiel-resolver (mogelijke bug, niet upstream) voor type=%s",
                    profielType,
                )
                Uni.createFrom().failure(ProfielServiceFoutException.onleesbaar(error))
            }
    }

    private fun bepaalMagazijnen(partij: PartijResponse): Set<String> {
        // Single-pass walk: bouwt direct de magazijn-set zonder tussenliggende List-allocaties.
        // Defensief: ongeldige upstream-OINs worden stil overgeslagen zodat een upstream-
        // typefout niet de héle resolver laat falen. Wel warn-loggen (niet error: upstream-
        // fout, niet onze fout) zodat structurele drift zichtbaar wordt; maskeer de
        // OIN-waarde tot prefix om geen volledige identificator in logs te zetten.
        // Reverse-index lookup via clientFactory.magazijnenVoorAfzender: O(1) per OIN i.p.v.
        // O(N×M) scan over alle magazijn-afzender-paren.
        return buildSet {
            partij.voorkeuren.forEach { voorkeur ->
                if (voorkeur.voorkeurType != VOORKEUR_ONTVANG_BERICHTEN) return@forEach
                if (voorkeur.waarde?.lowercase() !in INGESCHAKELDE_WAARDEN) return@forEach

                voorkeur.scopes.forEach { scope ->
                    val partijId = scope.partij ?: return@forEach

                    if (partijId.identificatieType != "OIN") return@forEach

                    val oin = runCatching { Oin(partijId.identificatieNummer) }
                        .onFailure { ex ->
                            val masked = partijId.identificatieNummer.take(4) + "***"

                            log.warnf(
                                "Profiel-service leverde ongeldige OIN '%s' (cause=%s); overslaan",
                                masked,
                                ex.javaClass.simpleName,
                            )
                        }
                        .getOrNull() ?: return@forEach

                    addAll(clientFactory.magazijnenVoorAfzender(oin))
                }
            }
        }
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

        // Profiel-service ondersteunt zowel boolean-strings ("true"/"false") als
        // Nederlandstalige waarden ("ja"/"nee") per legacy upstream-contract; beide
        // worden case-insensitive vergeleken (waarde.lowercase). Uitbreiding hier =
        // contract-wijziging, niet een config-knop — Profiel-team bevestigt eerst.
        private val INGESCHAKELDE_WAARDEN = setOf("true", "ja")
    }
}
