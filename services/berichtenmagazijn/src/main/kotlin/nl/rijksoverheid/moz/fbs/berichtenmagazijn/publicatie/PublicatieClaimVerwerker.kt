package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.common.FoutBeschrijving
import org.jboss.logging.Logger
import java.time.Clock

/**
 * Verwerkt één geclaimde delivery binnen een eigen transactie (`REQUIRES_NEW`).
 *
 * **Aparte bean, geen private methode in [PublicatieStream]:** `@Transactional` werkt
 * alleen via de CDI-proxy; een in-class call zou de interceptor overslaan.
 *
 * **`REQUIRES_NEW` per claim, niet per batch:** een trage downstream (HTTP-timeout 10s ×
 * batch 50) zou een batch-transactie voorbij `idle_in_transaction_session_timeout` open
 * houden → Postgres kapt af → status-updates weg → duplicate sends. Per-claim houdt het
 * lock kort en isoleert fouten tussen claims.
 *
 * **At-least-once, geen exactly-once:** crasht het tussen de 2xx en de commit van
 * `markeerGeslaagd`, dan wordt de claim opnieuw verzonden. Downstream-idempotency op
 * `(source, id)` is daarom verplicht; [CloudEventBuilder] geeft een deterministische id.
 */
@ApplicationScoped
class PublicatieClaimVerwerker(
    private val claimer: PublicatieClaimer,
    private val berichten: BerichtRepository,
    private val cloudEventBuilder: CloudEventBuilder,
    private val downstreamClient: DownstreamClient,
    private val config: PublicatieConfig,
    private val processingHandler: ProcessingHandler,
    private val clock: Clock,
) {

    private val log = Logger.getLogger(PublicatieClaimVerwerker::class.java)

    /**
     * Cache van per-doel gestripte downstream-URLs. URLs zijn config-stabiel (SmallRye
     * rebindt niet at runtime), dus éénmaal parsen per (doel, url) bespaart elke claim
     * een URI-roundtrip. `ConcurrentHashMap` wegens parallelle scheduler-/CDI-toegang;
     * cardinaliteit ≤ aantal downstreams (2–5).
     */
    private val gestripteDownstreamUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Begrenst de "doel niet meer in config"-warn tot 1× per [ONBEKEND_DOEL_WARN_COOLDOWN]
     * per [Publicatiedoel] — anders een log-storm tijdens config-removal. Sleutel is het
     * value-class (niet de rauwe string) zodat een refactor geen vreemde identifier doorgeeft.
     */
    private val onbekendDoelWarnLimiter = LogStormLimiter<Publicatiedoel>(
        cooldown = ONBEKEND_DOEL_WARN_COOLDOWN,
        clock = clock,
    )

    /**
     * Claimt één rij + verwerkt + markeert in één transactie. Retourneert
     * `true` als er een claim verwerkt is, `false` als er geen openstaand
     * werk meer is. [PublicatieStream] loopt hierop tot er niets meer is
     * of de batch-grens bereikt is.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun verwerkEenClaim(): Boolean {
        val claim = claimer.claimNuVerwerkbaar(maxBatch = 1).firstOrNull() ?: return false
        verwerkClaim(claim)
        return true
    }

    private fun verwerkClaim(claim: PublicatieClaim) {
        val span = processingHandler.startSpan(
            "publicatie-${claim.doel}",
            Context.current(),
        )
        // LogboekContext wordt vóór verwerking opgebouwd zodat de `finally`-tak
        // hem altijd aan de span kan toevoegen — zelfs als verwerking faalt of
        // het bericht ontbreekt. Default-status `OK`; wordt naar `ERROR` gezet
        // bij fout-paden hieronder.
        val ldvContext = LogboekContext().apply {
            processingActivityId = config.verwerkingsregisterPubliceren()
            status = StatusCode.OK
        }
        try {
            val bericht = berichten.findByBerichtId(claim.berichtId)
            if (bericht == null) {
                // Bericht weg tussen plan en verwerking. CASCADE op bericht_id maakt dit in
                // praktijk onbereikbaar; vangnet voor handmatige DB-mutaties/soft-delete.
                // dataSubject = berichtId (ontvanger ontbreekt) zodat het LDV-record
                // auditbaar blijft zonder lege subject-velden.
                ldvContext.dataSubjectId = claim.berichtId.toString()
                ldvContext.dataSubjectType = "BERICHT_ID_ONLY"
                log.warnf(
                    "Bericht ontbreekt voor claim claimId=%d berichtId=%s; markeer MISLUKT",
                    claim.claimId, claim.berichtId,
                )
                claimer.markeerMislukt(claim.claimId, "Bericht niet gevonden", volgendePoging = null)
                ldvContext.status = StatusCode.ERROR
                span.setStatus(StatusCode.ERROR, "Bericht niet gevonden")
                return
            }

            // LDV-attributen voor verwerkingsactiviteit "publiceren": ontvanger als
            // dataSubject, gestripte downstream-URL als foreign_operation.processor.
            val downstreamConfig = config.downstreams()[claim.doel.key]
            if (downstreamConfig == null && onbekendDoelWarnLimiter.magEmitten(claim.doel)) {
                // Config-drift: doel staat in outbox-rij maar niet meer in config →
                // eindeloze retry tegen `<onbekend>`-URL. Warn (gedempt door
                // onbekendDoelWarnLimiter) zodat ops het lek dicht vóór de pogingen op zijn.
                log.warnf(
                    "Doel '%s' niet (meer) in config.downstreams — claim wordt MISLUKT-gemarkeerd via DownstreamClient (claimId=%d)",
                    claim.doel.key, claim.claimId,
                )
            }
            val downstreamUrl = downstreamConfig?.url()?.let { url ->
                gestripteDownstreamUrls.computeIfAbsent("${claim.doel.key}|$url") {
                    stripUrlGeheimen(url, claim.doel)
                }
            } ?: "<onbekend>"
            ldvContext.dataSubjectId = bericht.ontvanger.waarde
            ldvContext.dataSubjectType = bericht.ontvanger.type.name
            span.setAttribute("dpl.core.foreign_operation.processor", downstreamUrl)
            span.setAttribute("publicatie.doel", claim.doel.key)
            span.setAttribute("publicatie.bericht_id", claim.berichtId.toString())

            val nu = clock.instant()
            val event = cloudEventBuilder.bouw(bericht, claim.doel, nu)
            when (val resultaat = downstreamClient.lever(claim.doel, event)) {
                is DownstreamResultaat.Geslaagd -> {
                    try {
                        claimer.markeerGeslaagd(claim.claimId, nu)
                    } catch (ex: IllegalStateException) {
                        // 2xx ontvangen maar status niet bijgewerkt → gegarandeerd
                        // duplicate-send volgende ronde; ops moet dit kunnen correleren.
                        log.errorf(
                            ex,
                            "Duplicate-send venster: HTTP 2xx ontvangen maar markeerGeslaagd faalde; berichtId=%s doel=%s",
                            claim.berichtId, claim.doel,
                        )
                        ldvContext.status = StatusCode.ERROR
                        span.setStatus(StatusCode.ERROR, "Duplicate-send venster")
                        throw ex
                    }
                    log.debugf(
                        "Bericht gepubliceerd: berichtId=%s doel=%s pogingen=%d",
                        claim.berichtId, claim.doel, claim.pogingen + 1,
                    )
                }
                is DownstreamResultaat.Mislukt -> {
                    // Geen downstreamConfig (config-drift) → null volgendePoging → terminal
                    // MISLUKT; dat klopt, een onbekend doel is een non-herstelbare config-fout.
                    val volgendePoging = downstreamConfig?.let { dc ->
                        RetryBeleid.volgendePoging(
                            nu = nu,
                            pogingenNaFout = claim.pogingen + 1,
                            maxPogingen = dc.maxPogingen(),
                            basis = dc.backoff().basis(),
                            plafond = dc.backoff().plafond(),
                            claimId = claim.claimId,
                            herstelbaar = resultaat.herstelbaar,
                            retryAfter = resultaat.retryAfter,
                        )
                    }
                    val gesaneerdeReden = FoutBeschrijving.saneer(resultaat.reden)
                    claimer.markeerMislukt(claim.claimId, gesaneerdeReden, volgendePoging)
                    ldvContext.status = StatusCode.ERROR
                    if (volgendePoging == null) {
                        log.errorf(
                            "Bericht-publicatie definitief mislukt: berichtId=%s doel=%s pogingen=%d categorie=%s reden=%s",
                            claim.berichtId, claim.doel, claim.pogingen + 1,
                            resultaat::class.simpleName, gesaneerdeReden,
                        )
                        span.setStatus(StatusCode.ERROR, "MISLUKT na ${claim.pogingen + 1} pogingen")
                    } else {
                        log.warnf(
                            "Bericht-publicatie mislukt; retry gepland: berichtId=%s doel=%s pogingen=%d volgendePoging=%s categorie=%s reden=%s",
                            claim.berichtId, claim.doel, claim.pogingen + 1, volgendePoging,
                            resultaat::class.simpleName, gesaneerdeReden,
                        )
                        span.setStatus(StatusCode.ERROR, "Retry gepland")
                    }
                }
            }
        } finally {
            // LDV-koppeling mag de commit nooit terugrollen (→ duplicate send). Smal vangen
            // op `IllegalArgumentException` (config-fout uit ProcessingHandler); andere
            // exceptions vliegen door zodat REQUIRES_NEW rolt en de bug zichtbaar wordt.
            // Genest try/finally zodat `span.end()` altijd draait (anders span-leak).
            try {
                try {
                    processingHandler.addLogboekContextToSpan(span, ldvContext)
                } catch (ex: IllegalArgumentException) {
                    log.errorf(
                        ex,
                        "LDV-context koppelen aan span faalde (config); transactie wordt niet teruggerold (claimId=%d doel=%s)",
                        claim.claimId, claim.doel,
                    )
                }
            } finally {
                span.end()
            }
        }
    }

    /**
     * Strip userinfo en query uit [url] zodat een eventuele API-key niet als
     * span-attribuut lekt. Path-segmenten blijven (credentials in URL-paths schendt
     * de conventie in `docs/operator-handleiding.md`). Bij parse-fout: warn + marker;
     * smal `IllegalArgumentException` zodat OOM/StackOverflow niet gemaskeerd worden.
     */
    private fun stripUrlGeheimen(url: String, doel: Publicatiedoel): String = try {
        val parsed = java.net.URI.create(url)
        java.net.URI(
            parsed.scheme,
            null, // userInfo
            parsed.host,
            parsed.port,
            parsed.path,
            null, // query
            null, // fragment
        ).toString()
    } catch (ex: IllegalArgumentException) {
        log.warnf(ex, "Downstream-URL niet parseerbaar voor LDV-strip: doel=%s", doel)
        "<unparseable>"
    }

    companion object {
        /**
         * Cooldown voor de "doel niet meer in config"-warn: 5 min ≈ 5 pollrondes bij
         * default-interval 60s. Public zodat tests de waarde niet hoeven te dupliceren.
         */
        val ONBEKEND_DOEL_WARN_COOLDOWN: java.time.Duration = java.time.Duration.ofMinutes(5)
    }
}
