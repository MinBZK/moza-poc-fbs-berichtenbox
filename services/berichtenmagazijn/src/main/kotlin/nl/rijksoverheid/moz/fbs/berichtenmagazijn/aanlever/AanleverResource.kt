package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.opentelemetry.api.trace.StatusCode
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.AanleverApi
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtAanleverenRequest
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtLinks
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtResponse
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Identificatienummer as IdentificatienummerDto
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Link
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.LogStormLimiter
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieConfig
import nl.rijksoverheid.moz.fbs.common.FoutBeschrijving
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration

/**
 * REST-resource voor de Aanlever API.
 *
 * **Geen `@Logboek`-annotatie**: de bundeled CDI interceptor zet
 * `logboekContext.processingActivityId` op de hardcoded annotation-value vóór
 * `addLogboekContextToSpan`, wat config-driven URI's onmogelijk maakt. We
 * doen daarom zelf span-management; dit spiegelt de aanpak in
 * [nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieClaimVerwerker]
 * en houdt de processingActivityId-bron op één plek (config).
 *
 * **Inbound W3C `traceparent` wordt NIET als parent geadopteerd**: het endpoint
 * is (in PoC) ongeauthentiseerd. Een aanvaller met netwerktoegang kan anders
 * een eigen trace-id meesturen die via [DownstreamClient.injecteerTraceparent]
 * cross-organisatie naar Aanmeld/Notificatie-services propageert, of requests
 * van verschillende afzenders kunstmatig aan dezelfde keten koppelt. We starten
 * daarom altijd een nieuwe root-span. Zodra mTLS (PKIoverheid) of OAuth-
 * authenticatie aanstaat kan deze keuze worden heroverwogen — dan is de upstream
 * vertrouwd genoeg om als parent te accepteren, of als `addLink` te koppelen.
 */
@Path(ApiInfo.BASE_PATH + "/berichten")
@ApplicationScoped
class AanleverResource(
    private val opslagService: BerichtOpslagService,
    private val logboekContext: LogboekContext,
    private val processingHandler: ProcessingHandler,
    private val publicatieConfig: PublicatieConfig,
    private val clock: Clock,
    @param:Context private val uriInfo: UriInfo,
    @param:Context private val httpHeaders: HttpHeaders,
) : AanleverApi {

    private val log = Logger.getLogger(AanleverResource::class.java)

    /**
     * Begrenst de whitelist-rejection warn tot één emit per
     * [WHITELIST_REJECTION_COOLDOWN] over alle requests. Het endpoint is
     * (in PoC) ongeauthentiseerd: zonder cooldown kan een aanvaller met N
     * requests/sec N warn-regels/sec forceren → log-volume DoS, drift in
     * alerting, mogelijke kostendoorberekening op centrale logging.
     * Vaste sleutel (`Unit`) accepteert signaalverlies bij volume-aanvallen
     * om logvolume te begrenzen — operator ziet nog steeds dat het gebeurt.
     * Per-IP-sleutel zou X-Forwarded-For + trusted-proxy-validatie vereisen;
     * dat is werk voor de mTLS/OAuth-fase die de authn al introduceert.
     */
    private val whitelistRejectionLimiter = LogStormLimiter<Unit>(
        cooldown = WHITELIST_REJECTION_COOLDOWN,
        clock = clock,
    )

    override fun leverBerichtAan(berichtAanleverenRequest: BerichtAanleverenRequest): BerichtResponse {
        // Span en LDV-context binnen try zodat een latere config-throw geen
        // span-leak veroorzaakt; finally end()'t altijd. Nieuwe root-span
        // (geen inbound parent) — zie KDoc voor rationale.
        var pendingFailure: Throwable? = null
        val span = processingHandler.startSpan("aanleveren-bericht", null)
        try {
            // processingActivityId vóór de eerste mogelijke fout zetten zodat
            // addLogboekContextToSpan in finally niet faalt op `require(!isNullOrEmpty)`.
            // dataSubjectId/-Type worden door LogboekContextDefaultFilter al op
            // safe defaults gezet; we vervangen ze hieronder door de gevalideerde
            // ontvanger-waarde + concrete type (BSN/RSIN/KVK), niet de relationele rol.
            logboekContext.processingActivityId = publicatieConfig.verwerkingsregisterAanleveren()
            return span.makeCurrent().use { _ ->
                val ontvangerDto = berichtAanleverenRequest.ontvanger
                val bericht = opslagService.opslaanBericht(
                    afzender = berichtAanleverenRequest.afzender,
                    ontvangerType = IdentificatienummerType.valueOf(ontvangerDto.type.name),
                    ontvangerWaarde = ontvangerDto.waarde,
                    onderwerp = berichtAanleverenRequest.onderwerp,
                    inhoud = berichtAanleverenRequest.inhoud,
                    publicatieDatum = berichtAanleverenRequest.publicatieDatum,
                )

                // Zet dataSubjectId pas na succesvolle domein-validatie, zodat we geen
                // ongevalideerde input in de AVG-logboekcontext zetten. Tot dat punt
                // zorgt LogboekContextDefaultFilter voor een safe default.
                logboekContext.dataSubjectId = bericht.ontvanger.waarde
                // type.name (BSN/KVK/RSIN) i.p.v. relationele rol "ontvanger" zodat
                // LDV-records over aanleveren én publiceren correleerbaar zijn op
                // dezelfde subject-taxonomie (PublicatieClaimVerwerker doet hetzelfde).
                logboekContext.dataSubjectType = bericht.ontvanger.type.name

                val selfHref = uriInfo.baseUriBuilder
                    .path(ApiInfo.BASE_PATH)
                    .path("berichten")
                    .path(bericht.berichtId.toString())
                    .build()

                BerichtResponse().apply {
                    berichtId = bericht.berichtId
                    afzender = bericht.afzender.waarde
                    ontvanger = IdentificatienummerDto().apply {
                        type = IdentificatienummerDto.TypeEnum.valueOf(bericht.ontvanger.type.name)
                        waarde = bericht.ontvanger.waarde
                    }
                    onderwerp = bericht.onderwerp
                    tijdstipOntvangst = bericht.tijdstipOntvangst
                    publicatieDatum = bericht.publicatieDatum
                    links = BerichtLinks().apply {
                        self = Link().apply { href = selfHref }
                    }
                }
            }
        } catch (ex: Exception) {
            pendingFailure = ex
            span.setStatus(StatusCode.ERROR)
            throw ex
        } finally {
            // foreign_operation.processor-attribuut equivalent aan LogboekInterceptor
            // — alleen koppelen als upstream een traceparent stuurde. Sanering
            // tegen log-poisoning (CWE-117) en oversize-DoS: header-content komt
            // van een ongeauthentiseerde caller. Striktere validatie dan
            // FoutBeschrijving.saneer (die alleen lange cijferreeksen redact):
            // whitelist op `^[A-Za-z0-9._=:/-]{1,256}$` weert hex-only of
            // padding-payloads die de cijfer-redact zouden ontwijken.
            val traceparent = httpHeaders.getHeaderString("traceparent")
            if (traceparent != null) {
                val raw = httpHeaders.getHeaderString("traceparent-processor") ?: ""
                val veilig = if (TRACEPARENT_PROCESSOR_PATTERN.matches(raw)) raw else ""
                if (raw.isNotEmpty() && veilig.isEmpty() && whitelistRejectionLimiter.magEmitten(Unit)) {
                    // Whitelist-rejection is een security-event: mogelijk poisoning-poging
                    // of vendor-mismatch. Op `warn` (niet `debug`) zodat het signaal in
                    // productie-log-streams overkomt — debug is daar standaard uit.
                    // FoutBeschrijving.saneer redact cijferreeksen + control-chars vóór log.
                    // [whitelistRejectionLimiter] dempt log-volume DoS via N requests/sec.
                    log.warnf(
                        "traceparent-processor whitelist-rejection: snippet=%s",
                        FoutBeschrijving.saneer(raw, 80),
                    )
                }
                span.setAttribute("dpl.core.foreign_operation.processor", veilig)
            }
            // Genest try/finally rond `addLogboekContextToSpan`: een fout in deze
            // finally-tak (niet de oorspronkelijke business-exception) mag de
            // span niet lekken naar OTel-exporter. Outer finally borgt `span.end()`,
            // inner try vangt enkel IAE (= config/state-fout van ProcessingHandler).
            // Andere RuntimeExceptions (NPE, ISE, CME) wijzen op programmeerfouten en
            // mogen niet stilletjes verdwijnen — die vliegen door naar de exception-mapper.
            try {
                try {
                    processingHandler.addLogboekContextToSpan(span, logboekContext)
                } catch (ex: IllegalArgumentException) {
                    // Consistent met ExceptionMapper-discipline: laat pendingFailure-
                    // message weg uit log (FoutBeschrijving.saneer dekt geen niet-
                    // numerieke PII). Categorie + cause-type zijn voldoende correlatie-
                    // handvatten; de oorspronkelijke exception-stack komt door naar
                    // de mapper via de `throw` in catch (regel 120).
                    log.errorf(
                        ex,
                        "LDV-context koppelen aan span faalde voor aanleveren-bericht; " +
                            "oorspronkelijke fout-categorie=%s cause=%s",
                        pendingFailure?.javaClass?.simpleName ?: "geen",
                        pendingFailure?.cause?.javaClass?.simpleName ?: "geen",
                    )
                }
            } finally {
                span.end()
            }
        }
    }

    companion object {
        /**
         * Whitelist voor `traceparent-processor`-header voordat we hem als
         * span-attribute zetten. Toegestaan: alfanumeriek + `_`/`=`/`-`/`/`/`:`/`.`
         * (genoeg voor URLs, vendor-specifieke processor-IDs en versie-tags),
         * max 256 chars. Strikter dan [FoutBeschrijving.saneer]: voorkomt dat
         * een attacker hex-strings of padding-bytes injecteert in centrale
         * tracing/audit (LDV cross-organisatie).
         *
         * Allowlist-aanpak (alleen toegestane chars doorlaten) is bewust gekozen
         * boven blocklist/redact: nieuwe attack-payloads worden by-default
         * geweigerd i.p.v. dat de redact-regex elke keer uitgebreid moet worden
         * als attackers nieuwe omzeilings-vormen vinden.
         */
        private val TRACEPARENT_PROCESSOR_PATTERN = Regex("^[A-Za-z0-9._=:/-]{1,256}$")

        /**
         * Cooldown voor whitelist-rejection warn — zie [whitelistRejectionLimiter].
         * 1 minuut: kort genoeg dat ops binnen één scrape-window gewaarschuwd
         * wordt; lang genoeg om N-per-seconde request-floods te dempen.
         */
        val WHITELIST_REJECTION_COOLDOWN: Duration = Duration.ofMinutes(1)
    }
}
