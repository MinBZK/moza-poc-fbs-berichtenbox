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
import nl.rijksoverheid.moz.fbs.common.profiel.PartijRequest
import nl.rijksoverheid.moz.fbs.common.profiel.PartijResponse
import nl.rijksoverheid.moz.fbs.common.profiel.Profiel404Duiding
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielNietGevonden
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielVoorkeuren
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.TimeUnit

@ApplicationScoped
internal class ProfielMagazijnResolver(
    @param:RestClient private val profielClient: ProfielServiceClient,
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
     * Van de 404-antwoorden wordt alleen het herkenbare "partij niet gevonden" gemapt naar
     * `emptySet()` (succes-pad) en dus gecacht; elk ander 404-antwoord is een storing en
     * belandt op het fout-pad, dat niet gecacht wordt.
     *
     * Cache-key = `Identificatienummer`; elk type is een eigen value class, dus een BSN en
     * een RSIN met dezelfde cijfers zijn verschillende keys.
     * BSN/RSIN-waarde leeft daarmee voor de TTL-duur in JVM-heap; consistent met
     * de bestaande 60s sessiecache-window. Geen Redis: privacy-vlak blijft per-pod.
     * `maximumSize` capt heap-gebruik bij hoge unieke-ontvanger-rate.
     *
     * `getIfPresent`+`put` is bewust niet-atomair (geen single-flight): concurrent
     * `resolve()` voor dezelfde ontvanger wordt al gegate door de SET-NX-lock in
     * `BerichtensessiecacheService.haalBerichtenOp` (tweede ophaalpoging krijgt 409).
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

        val aanvraag = PartijRequest.van(ontvanger)
        val profielType = aanvraag.identificatieType

        // Inner-timeout dekt de hele `getPartij`-call inclusief het `@Retry`-budget (max 3
        // pogingen × read-timeout + 2× `delay`-tussenpauze). Configureerbaar via
        // `profiel.resolver.inner-timeout-seconds`. Outer-await in BerichtensessiecacheService
        // MOET groter zijn — startup-validatie in `valideerTimeouts()` borgt dit — anders
        // verliest de caller de juiste foutclassificatie (Mutiny-TimeoutException vs
        // j.u.c.TimeoutException).
        return Uni.createFrom().item { profielClient.getPartij(aanvraag) }
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
                    status == 404 -> verwerk404(webEx, profielType)
                    status != null && status in 400..499 -> {
                        // Niet-404 4xx = eigen contract-/auth-bug (400 op een aanvraag-lichaam
                        // dat upstream weigert, 401/403 auth-misser, 405 method-mismatch,
                        // 415 verkeerde Content-Type). Errorf zodat dit
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

    /**
     * Scheidt de twee betekenissen van een 404: "deze ontvanger heeft nog geen voorkeuren"
     * (opt-out, succes-pad) en alles wat daar niet ondubbelzinnig als te lezen is (storing).
     *
     * De opt-out mapt naar `emptySet()` en wordt door [resolve] gecacht; een storing wordt een
     * fout, zodat de aggregatie een bestaande cache níét met een lege lijst overschrijft en de
     * gebruiker een zichtbare melding krijgt in plaats van "0 berichten".
     */
    private fun verwerk404(webEx: WebApplicationException, profielType: String): Uni<Set<String>> {
        val duiding = duid404(webEx)

        if (duiding is Profiel404Duiding.PartijZonderProfiel) {
            // Normaal gedrag voor wie nog niets heeft vastgelegd, dus geen WARN: op INFO of
            // hoger zou elke zulke ophaalactie een regel opleveren en echte storingen
            // ondersneeuwen. Geen ontvanger-waarde in de log (PII).
            log.debugf("Profiel-service meldt geen profiel voor type=%s; ontvanger heeft nog geen voorkeuren", profielType)

            return Uni.createFrom().item(emptySet())
        }

        // Errorf: dit is de melding waaraan beheer een verschoven adres of een kapotte
        // koppeling herkent, dus moet ze de gevallen uit elkaar houden. De omschrijving komt
        // uit de duiding en is daar al begrensd en gesaniteerd; het rauwe lichaam gaat de log
        // niet in, want een upstream mag daar het identificatienummer in echoën.
        log.errorf(
            webEx,
            "Profiel-service 404 die geen 'partij niet gevonden' is voor type=%s (%s) — behandeld als storing",
            profielType,
            (duiding as Profiel404Duiding.Storing).omschrijving,
        )

        return Uni.createFrom().failure(ProfielServiceFoutException.upstreamError(404, webEx))
    }

    /**
     * Leest het foutlichaam en laat het duiden. Een respons die niet meer uit te lezen valt
     * krijgt een eigen omschrijving: dat wijst op onze eigen client en niet op een verschoven
     * adres, en stuurt beheer dus een andere kant op dan een 404 zónder lichaam.
     *
     * Vangt `RuntimeException` en niet enkel de twee verwachte types: welke uitzondering een
     * body-reader werpt hangt af van wat de upstream meestuurt — een onbekende charset in de
     * Content-Type levert bijvoorbeeld een `IllegalArgumentException`. Die zou anders in het
     * vangnet voor eigen-code-bugs belanden en een upstream-defect als onze bug alarmeren.
     * `Error`-types passeren wél, zoals overal in deze klasse.
     */
    private fun duid404(webEx: WebApplicationException): Profiel404Duiding {
        val lichaam = try {
            webEx.response?.readEntity(String::class.java)
        } catch (ex: RuntimeException) {
            return Profiel404Duiding.Storing("lichaam onleesbaar (cause=${ex.javaClass.simpleName})")
        }

        return ProfielNietGevonden.duid(lichaam)
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
        // Register-lookup via clientFactory.magazijnenVoorAfzender: 1:1 OIN↔magazijn,
        // dus per opted-in OIN hooguit één magazijn-id.
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
                    val veiligeWaarde = veiligLogFragment(oinString)

                    log.warnf(
                        "Profiel-service leverde ongeldige OIN-waarde '%s' (cause=%s); overslaan",
                        veiligeWaarde,
                        ex.javaClass.simpleName,
                    )
                    return@forEach
                }

                val matched = clientFactory.magazijnenVoorAfzender(oin)

                if (matched.isEmpty()) {
                    // Config-drift: Profiel kent een geldige OIN die niet in het
                    // magazijnregister staat. Volledige (publieke) OIN in de log zodat ops
                    // de mismatch direct kan herleiden.
                    driftSkips++

                    log.warnf(
                        "Profiel-service noemt afzender-OIN '%s' die niet in het magazijnregister staat — config-drift?",
                        oin.waarde,
                    )

                    return@forEach
                }

                addAll(matched)
            }
        }

        // Geen enkele opt-in-voorkeur in de respons. Normaal voor opt-out/nieuwe
        // ontvangers, maar een structurele schema-drift (hernoemde voorkeurType of
        // verplaatste scope-OIN) zou dit pad voor iederéén raken en dan als een geslaagde
        // lege berichtenbox doorgaan. debugf geeft het signaal on-demand zonder INFO-ruis op
        // de hot-path; er is geen alert die deze vorm van drift opmerkt — dat is de open
        // kant van dit pad. Geen PII: enkel het feit, geen ontvanger-waarde.
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
                    "Config-drift (errorId=%s): %d van %d opted-in afzender-OIN(s) onbekend in het magazijnregister (ongeldig=%d)",
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

    internal companion object {
        // C0-control-chars + DEL + Unicode line/paragraph separators (U+2028/U+2029) → '?'.
        // Neutraliseert CRLF-log-injectie (CR/LF zitten in 0x00-0x1f) én de Unicode-separators
        // die sommige log-pipelines óók als regeleinde interpreteren, bij het loggen van
        // ongevalideerde upstream-strings. Precompiled: het ongeldig-OIN-pad is zeldzaam maar
        // mag bij een upstream-storm geen Regex per regel compileren.
        private val CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f\\u2028\\u2029]")

        /**
         * Maakt een ongevalideerde upstream-string veilig om te loggen: kap af op 24 tekens en
         * vervang control-/line-separator-chars door '?'. Voorkomt CRLF-log-injectie en
         * onbegrensde log-regels uit een buggy of vijandige upstream. Apart testbaar zodat de
         * sanitisatie-invariant gepind blijft los van het log-pad.
         */
        internal fun veiligLogFragment(ruweUpstreamWaarde: String): String =
            ruweUpstreamWaarde.take(24).replace(CONTROL_CHARS, "?")
    }
}
