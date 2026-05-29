package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.fasterxml.jackson.core.JsonProcessingException
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
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
import java.util.concurrent.TimeUnit

@ApplicationScoped
class ProfielMagazijnResolver(
    @RestClient private val profielClient: ProfielServiceClient,
    private val clientFactory: MagazijnClientFactory,
    @param:ConfigProperty(name = "profiel.resolver.inner-timeout-seconds", defaultValue = "18")
    private val innerTimeoutSeconds: Long,
    @param:ConfigProperty(name = "profiel.resolver.cache.ttl-seconds", defaultValue = "30")
    private val cacheTtlSeconds: Long,
    @param:ConfigProperty(name = "profiel.resolver.cache.max-size", defaultValue = "10000")
    private val cacheMaxSize: Long,
) : MagazijnResolver {

    private val log = Logger.getLogger(ProfielMagazijnResolver::class.java)

    /**
     * Per-ontvanger cache op de uiteindelijke magazijn-set (niet op `PartijResponse`):
     * smaller cached value, dezelfde absorberende werking richting Profiel-service.
     *
     * Bewust *handmatige* Caffeine-cache i.p.v. Quarkus `@CacheResult`: vanaf Quarkus 3.7
     * worden Uni-failures óók in de annotatie-gebaseerde cache geplaatst, waardoor een
     * tijdelijke Profiel-storing TTL-lang een 503-loop zou veroorzaken. Hier cachen we
     * uitsluitend bij succesvolle emissie (`onItem().invoke { ... cache.put(...) }`).
     *
     * Een 404 wordt door deze resolver gemapt naar `emptySet()` (succes-pad) en dus
     * wél gecacht; system-wide 404-rate-alert via Loki blijft het mechanisme om
     * een config-misser te onderscheiden van "ontvanger heeft geen profiel".
     *
     * Cache-key = `Identificatienummer` (value-class equals/hashCode op `waarde`).
     * BSN/RSIN-waarde leeft daarmee voor de TTL-duur in JVM-heap; consistent met
     * de bestaande 60s sessiecache-window. Geen Redis: privacy-vlak blijft per-pod.
     * `maximumSize` capt heap-gebruik bij hoge unieke-ontvanger-rate.
     *
     * `getIfPresent`+`put` is bewust niet-atomair (geen single-flight): concurrent
     * `resolve()` voor dezelfde ontvanger wordt al gegate door de SET-NX-lock in
     * `BerichtensessiecacheService.ophalenBerichten` (tweede ophaalpoging krijgt 409).
     * De cache absorbeert dus sequentiële her-triggers binnen het TTL-window, geen
     * parallelle storm — een cache-stampede kan in dit flow niet optreden.
     */
    private val magazijnSetCache: Cache<Identificatienummer, Set<String>> by lazy {
        Caffeine.newBuilder()
            .expireAfterWrite(cacheTtlSeconds, TimeUnit.SECONDS)
            .maximumSize(cacheMaxSize)
            .build()
    }

    override fun resolve(ontvanger: Identificatienummer): Uni<Set<String>> {
        magazijnSetCache.getIfPresent(ontvanger)?.let { cached ->
            return Uni.createFrom().item(cached)
        }

        return doResolve(ontvanger).onItem().invoke { result ->
            magazijnSetCache.put(ontvanger, result)
        }
    }

    private fun doResolve(ontvanger: Identificatienummer): Uni<Set<String>> {
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
                    else -> {
                        // 5xx of ontbrekende statuscode: gewone upstream-fout-doorgifte. Warn-log
                        // vóór de wrap zodat een ontbrekende statuscode (status==null — Response
                        // onverwacht weg, mogelijk eigen bug) in incidenten te onderscheiden is van
                        // een echte upstream-5xx; de mapper logt later alleen op categorie-niveau.
                        log.warnf(error, "Profiel-service 5xx/geen-status (status=%s) voor type=%s", status, profielType)

                        Uni.createFrom().failure(ProfielServiceFoutException.upstreamError(status, error))
                    }
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
        // fout, niet onze fout) zodat structurele drift zichtbaar wordt. OIN is publiek
        // (geen PII) — een gevalideerde drift-OIN wordt vól gelogd zodat ops de mismatch
        // direct kan fixen. Alleen een ongeldige, ongevalideerde upstream-string wordt
        // afgekapt + ontdaan van control-chars (een buggy upstream kan daar onverwachte
        // inhoud of een CRLF-log-injectie in zetten — dat is geen geldige OIN meer).
        // Reverse-index lookup via clientFactory.magazijnenVoorAfzender: O(1) per OIN i.p.v.
        // O(N×M) scan over alle magazijn-afzender-paren.
        var totaal = 0
        var ongeldig = 0
        var driftSkips = 0

        val result = buildSet {
            ProfielVoorkeuren.optedInAfzenderOinStrings(partij).forEach { oinString ->
                totaal++
                val oin = try {
                    Oin(oinString)
                } catch (ex: IllegalArgumentException) {
                    // Specifiek IllegalArgumentException — validatiefout uit Oin-constructor.
                    // Brede runCatching zou Error-types (LinkageError, OOM) inslikken.
                    // Geen OIN-PII-maskering (OIN is publiek), maar dit is een ongevalideerde
                    // upstream-string: afkappen + control-chars neutraliseren tegen
                    // CRLF-log-injectie en onverwachte inhoud van een buggy upstream.
                    ongeldig++
                    val veiligeWaarde = oinString.take(24).replace(CONTROL_CHARS, "?")

                    log.warnf(
                        "Profiel-service leverde ongeldige OIN-waarde '%s' (cause=%s); overslaan",
                        veiligeWaarde,
                        ex.javaClass.simpleName,
                    )
                    return@forEach
                }

                val matched = clientFactory.magazijnenVoorAfzender(oin)

                if (matched.isEmpty()) {
                    // Config-drift: Profiel kent een geldige OIN die niet in magazijn-config
                    // staat. Volledige (publieke) OIN in de log zodat ops de config-mismatch
                    // direct kan herleiden.
                    driftSkips++

                    log.warnf(
                        "Profiel-service noemt afzender-OIN '%s' die bij geen geconfigureerd magazijn hoort — config-drift?",
                        oin.waarde,
                    )

                    return@forEach
                }

                addAll(matched)
            }
        }

        // Geen enkele opt-in-voorkeur in de respons. Normaal voor opt-out/nieuwe
        // ontvangers, maar een structurele schema-drift (hernoemde voorkeurType of
        // verplaatste scope-OIN) zou dit pad voor iederéén raken. debugf geeft het
        // signaal on-demand zonder INFO-ruis op de hot-path; structurele drift-detectie
        // in productie hangt aan de Profiel-404-rate-alert (zie docs/operations).
        // Geen PII: enkel het feit, geen ontvanger-waarde.
        if (totaal == 0) {
            log.debugf("Profiel-respons zonder opt-in-voorkeuren (0 afzender-OINs)")
        }

        // 100%-effective-empty → gestructureerde fout (geen silent GEREED).
        // Onderscheid log "alle parses faalden" (upstream-issue) vs "alle valid OINs
        // onbekend" (drift). Exception eerst zodat errorf+mapper+cleanup dezelfde id dragen.
        if (totaal > 0 && result.isEmpty()) {
            val foutException = ProfielServiceFoutException.configDrift()

            if (driftSkips > 0) {
                log.errorf(
                    "Config-drift (errorId=%s): %d van %d opted-in afzender-OIN(s) onbekend bij magazijn-config (ongeldig=%d)",
                    foutException.errorId, driftSkips, totaal, ongeldig,
                )
            } else {
                log.errorf(
                    "Upstream-data-issue (errorId=%s): alle %d opted-in afzender-OIN(s) ongeldig — Profiel-respons gecorrumpeerd?",
                    foutException.errorId, totaal,
                )
            }
            throw foutException
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

    private companion object {
        // C0-control-chars + DEL → '?'. Neutraliseert CRLF-log-injectie bij het loggen van
        // ongevalideerde upstream-strings. Precompiled: het ongeldig-OIN-pad is zeldzaam maar
        // mag bij een upstream-storm geen Regex per regel compileren.
        private val CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f]")
    }

}
