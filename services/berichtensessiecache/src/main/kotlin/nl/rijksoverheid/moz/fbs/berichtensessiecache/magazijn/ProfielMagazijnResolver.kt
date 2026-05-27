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
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielVoorkeuren
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Duration

@ApplicationScoped
class ProfielMagazijnResolver(
    @RestClient private val profielClient: ProfielServiceClient,
    private val clientFactory: MagazijnClientFactory,
    @param:ConfigProperty(name = "profiel.resolver.inner-timeout-seconds", defaultValue = "18")
    private val innerTimeoutSeconds: Long,
) : MagazijnResolver {

    private val log = Logger.getLogger(ProfielMagazijnResolver::class.java)

    override fun resolve(ontvanger: Identificatienummer): Uni<Set<String>> {
        // OIN-ontvanger (B2B): geen Profiel-pad bestaat upstream; lever alle magazijnen.
        if (ontvanger.type == IdentificatienummerType.OIN) {
            return Uni.createFrom().item(clientFactory.getAllClients().keys)
        }

        val profielType = naarProfielType(ontvanger.type)

        // Inner-timeout dekt de hele `getPartij`-call inclusief het `@Retry`-budget (max 3
        // pogingen × read-timeout + 2× `delay`-tussenpauze). Configureerbaar via
        // `profiel.resolver.inner-timeout-seconds`. Outer-await in BerichtensessiecacheService
        // MOET groter zijn — startup-validatie in `valideerTimeouts()` borgt dit — anders
        // verliest de caller de juiste foutclassificatie (Mutiny-TimeoutException vs
        // j.u.c.TimeoutException).
        return Uni.createFrom().item { profielClient.getPartij(profielType, ontvanger.waarde) }
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .ifNoItem().after(Duration.ofSeconds(innerTimeoutSeconds)).fail()
            .map { partij -> bepaalMagazijnen(partij) }
            .onFailure(io.smallrye.mutiny.TimeoutException::class.java).recoverWithUni { error ->
                Uni.createFrom().failure(ProfielServiceFoutException.timeout(error))
            }
            .onFailure(WebApplicationException::class.java).recoverWithUni { error ->
                val webEx = error as WebApplicationException
                val status = webEx.response?.status

                when {
                    status == 404 -> {
                        // warnf i.p.v. debugf: een 404 op alle ontvangers is meestal een
                        // configuratiefout (base-path drift) en moet zichtbaar zijn in
                        // standaard log-niveau, niet alleen onder DEBUG. Geen ontvanger-
                        // waarde in de log (PII).
                        log.warnf("Profiel-service 404 voor type=%s; geen voorkeuren bekend (mogelijk config-misser)", profielType)
                        Uni.createFrom().item(emptySet<String>())
                    }
                    status != null && status in 400..499 -> {
                        // Niet-404 4xx = eigen contract-/auth-bug (400 invalide path,
                        // 401/403 auth-misser, 405 method-mismatch). Errorf zodat dit
                        // niet als gewone "Profiel-service tijdelijk niet beschikbaar"
                        // wegfiltert in upstream-503-incidenten.
                        log.errorf(error, "Profiel-service 4xx %d voor type=%s — eigen contract-/auth-fout", status, profielType)
                        Uni.createFrom().failure(ProfielServiceFoutException.upstreamError(status, error))
                    }
                    else ->
                        // 5xx of ontbrekende statuscode: gewone upstream-fout-doorgifte.
                        Uni.createFrom().failure(ProfielServiceFoutException.upstreamError(status, error))
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
            // Catch-all voor onverwachte Exceptions die niet eerder zijn afgevangen
            // (bv. NullPointerException uit de gegenereerde client of een interne fout in
            // bepaalMagazijnen). Wrap als ProfielServiceFoutException zodat de caller
            // consistent 503 + Retry-After krijgt in plaats van een onverwachte 500.
            // Filter op Exception (niet Throwable): Error-types (OutOfMemoryError,
            // LinkageError, StackOverflowError) moeten omhoog propageren naar de
            // JVM-vangnet, niet ingepakt worden als upstream-storing.
            // Expliciet errorf-loggen vóór de wrap: zonder dit verbergt het 503-pad een
            // eigen-code-bug als upstream-fout en gaat de bug in productie ongezien.
            .onFailure { it is Exception && it !is ProfielServiceFoutException }.recoverWithUni { error ->
                log.errorf(
                    error,
                    "Onverwachte fout in Profiel-resolver (mogelijke bug, niet upstream) voor type=%s",
                    profielType,
                )
                Uni.createFrom().failure(ProfielServiceFoutException.onverwacht(error))
            }
    }

    private fun bepaalMagazijnen(partij: PartijResponse): Set<String> {
        // Voorkeuren-filtering + scope-walk via gedeelde ProfielVoorkeuren-helper
        // (één bron van waarheid met BerichtValidatieService in berichtenmagazijn).
        // Defensief: ongeldige upstream-OINs worden stil overgeslagen zodat een upstream-
        // typefout niet de héle resolver laat falen. Wel warn-loggen (niet error: upstream-
        // fout, niet onze fout) zodat structurele drift zichtbaar wordt; maskeer de
        // OIN-waarde tot prefix om geen volledige identificator in logs te zetten.
        // Reverse-index lookup via clientFactory.magazijnenVoorAfzender: O(1) per OIN i.p.v.
        // O(N×M) scan over alle magazijn-afzender-paren.
        // toList() i.p.v. raw Sequence: we hebben .size en .isNotEmpty() nodig voor
        // de drift-escalatie hieronder, en we lopen 'm sowieso één keer door — geen
        // streaming-voordeel te halen.
        val oinStrings = ProfielVoorkeuren.optedInAfzenderOinStrings(partij).toList()

        val result = buildSet {
            oinStrings.forEach { oinString ->
                val oin = try {
                    Oin(oinString)
                } catch (ex: IllegalArgumentException) {
                    // Specifiek IllegalArgumentException — validatiefout uit Oin-constructor.
                    // Brede runCatching zou Error-types (LinkageError, OOM) inslikken.
                    val masked = oinString.take(4) + "***"

                    log.warnf(
                        "Profiel-service leverde ongeldige OIN '%s' (cause=%s); overslaan",
                        masked,
                        ex.javaClass.simpleName,
                    )
                    return@forEach
                }

                val matched = clientFactory.magazijnenVoorAfzender(oin)

                if (matched.isEmpty()) {
                    // Drift-detectie: Profiel kent een OIN die bij geen geconfigureerd
                    // magazijn hoort. Productie-aanleiding is config-mismatch (nieuwe
                    // afzender bij Profiel geactiveerd vóór magazijn-config gedeployd).
                    // OIN gemaskeerd tot 4-prefix (volledige identificator hoort niet in
                    // logs ook al is OIN publiek).
                    val masked = oinString.take(4) + "***"

                    log.warnf(
                        "Profiel-service noemt afzender-OIN '%s' die bij geen geconfigureerd magazijn hoort — config-drift (nieuwe afzender niet in magazijn-config?)",
                        masked,
                    )

                    return@forEach
                }

                addAll(matched)
            }
        }

        // 100%-drift escalatie: ontvanger heeft wél opt-ins maar geen enkele OIN
        // matched de magazijn-config. Aggregate errorf naast de per-OIN warns zodat
        // log-aggregator (Loki/CloudWatch) hier een alert op kan routen — anders
        // ziet ops alleen losse warns en blijft een complete config-mismatch (waardoor
        // ontvanger 60s stil "0 berichten" gecached krijgt) ongezien.
        if (oinStrings.isNotEmpty() && result.isEmpty()) {
            log.errorf(
                "Config-drift: alle %d opted-in afzender-OIN(s) onbekend bij magazijn-config — ontvanger ziet leeg resultaat zonder duidelijke fout (cache wordt 60s gevuld met empty)",
                oinStrings.size,
            )
        }

        return result
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

}
