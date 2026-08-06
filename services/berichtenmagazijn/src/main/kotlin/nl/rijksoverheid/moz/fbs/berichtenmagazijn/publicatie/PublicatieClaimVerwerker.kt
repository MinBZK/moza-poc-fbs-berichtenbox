package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.common.FoutBeschrijving
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant

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

    /**
     * De logregel gaat eruit vóór de levering. Faalt het schrijven, dan gooit
     * [ProcessingHandler.enforceWriteAcknowledgement], rolt deze `REQUIRES_NEW`-transactie
     * en blijft de claim openstaan — er is dan niets geleverd, dus de retry is veilig.
     * Andersom zou een rollback ná een geslaagde levering een dubbele levering opleveren.
     *
     * De logregel legt daarmee de voorgenomen verstrekking vast, niet de uitkomst: de
     * LDV-schrijfactie loopt over een eigen JDBC-verbinding met een eigen commit, dus een
     * rollback haalt hem niet meer weg. De uitkomst van de levering blijft in de
     * claim-status en het applicatielog.
     */
    private fun verwerkClaim(claim: PublicatieClaim) {
        val bericht = berichten.findByBerichtId(claim.berichtId)

        if (bericht == null) {
            verwerkOntbrekendBericht(claim)
            return
        }

        val downstreamConfig = config.downstreams()[claim.doel.key]

        legVerstrekkingVast(claim, bericht, downstreamConfig)

        val nu = clock.instant()
        val event = cloudEventBuilder.bouw(bericht, claim.doel, nu)

        when (val resultaat = downstreamClient.lever(claim.doel, event)) {
            is DownstreamResultaat.Geslaagd -> verwerkGeslaagd(claim, nu)
            is DownstreamResultaat.Mislukt -> verwerkMislukt(claim, resultaat, nu, downstreamConfig)
        }
    }

    /**
     * Schrijft de logregel voor deze verstrekking en wacht op bevestiging. Status blijft
     * `UNSET`: het logboek registreert dat de gegevens verstrekt gaan worden, niet of de
     * downstream ze aannam.
     */
    private fun legVerstrekkingVast(
        claim: PublicatieClaim,
        bericht: Bericht,
        downstreamConfig: PublicatieConfig.Downstream?,
    ) {
        // De recorder is thread-gebonden; deze bean doet zijn eigen span-beheer op de
        // scheduler-thread, dus een fout van een eerdere claim moet er eerst af.
        LogboekWriteFailureRecorder.clear()

        val span = processingHandler.startSpan("publicatie-${claim.doel}", Context.current())
        val ldvContext = LogboekContext().apply {
            processingActivityId = config.verwerkingsregisterPubliceren()
        }

        zetLdvEnSpanAttributen(claim, bericht, downstreamConfig, ldvContext, span)

        try {
            processingHandler.addLogboekContextToSpan(span, ldvContext)
        } finally {
            span.end()
        }

        processingHandler.enforceWriteAcknowledgement()
    }

    /**
     * Bericht weg tussen plan en verwerking. Een hard-delete neemt via CASCADE op
     * bericht_db_id ook de delivery-rij mee, dus die orphan-claim is onbereikbaar. Het
     * live pad hierheen is soft-delete: findByBerichtId filtert verwijderdOp IS NULL,
     * terwijl de delivery-rij blijft bestaan. dataSubject = berichtId (ontvanger
     * ontbreekt) zodat het LDV-record auditbaar blijft zonder lege subject-velden.
     *
     * Hier wordt niets verstrekt, dus de logregel gaat ná de statusmutatie de deur uit;
     * fail-closed kan hier zonder risico op een dubbele levering.
     */
    private fun verwerkOntbrekendBericht(claim: PublicatieClaim) {
        LogboekWriteFailureRecorder.clear()

        val span = processingHandler.startSpan("publicatie-${claim.doel}", Context.current())
        val ldvContext = LogboekContext().apply {
            processingActivityId = config.verwerkingsregisterPubliceren()
            dataSubjectId = claim.berichtId.toString()
            dataSubjectType = "BERICHT_ID_ONLY"
            status = StatusCode.ERROR
        }

        log.warnf(
            "Bericht ontbreekt voor claim claimId=%d berichtId=%s; markeer MISLUKT",
            claim.claimId, claim.berichtId,
        )
        claimer.markeerMislukt(claim.claimId, "Bericht niet gevonden", volgendePoging = null)
        span.setStatus(StatusCode.ERROR, "Bericht niet gevonden")

        try {
            processingHandler.addLogboekContextToSpan(span, ldvContext)
        } finally {
            span.end()
        }

        processingHandler.enforceWriteAcknowledgement()
    }

    /**
     * LDV-attributen voor verwerkingsactiviteit "publiceren": ontvanger als
     * dataSubject, gestripte downstream-URL als foreign_operation.processor.
     */
    private fun zetLdvEnSpanAttributen(
        claim: PublicatieClaim,
        bericht: Bericht,
        downstreamConfig: PublicatieConfig.Downstream?,
        ldvContext: LogboekContext,
        span: Span,
    ) {
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
    }

    private fun verwerkGeslaagd(claim: PublicatieClaim, nu: Instant) {
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
            throw ex
        }

        log.debugf(
            "Bericht gepubliceerd: berichtId=%s doel=%s pogingen=%d",
            claim.berichtId, claim.doel, claim.pogingen + 1,
        )
    }

    private fun verwerkMislukt(
        claim: PublicatieClaim,
        resultaat: DownstreamResultaat.Mislukt,
        nu: Instant,
        downstreamConfig: PublicatieConfig.Downstream?,
    ) {
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

        if (volgendePoging == null) {
            log.errorf(
                "Bericht-publicatie definitief mislukt: berichtId=%s doel=%s pogingen=%d categorie=%s reden=%s",
                claim.berichtId, claim.doel, claim.pogingen + 1,
                resultaat::class.simpleName, gesaneerdeReden,
            )
        } else {
            log.warnf(
                "Bericht-publicatie mislukt; retry gepland: berichtId=%s doel=%s pogingen=%d volgendePoging=%s categorie=%s reden=%s",
                claim.berichtId, claim.doel, claim.pogingen + 1, volgendePoging,
                resultaat::class.simpleName, gesaneerdeReden,
            )
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
