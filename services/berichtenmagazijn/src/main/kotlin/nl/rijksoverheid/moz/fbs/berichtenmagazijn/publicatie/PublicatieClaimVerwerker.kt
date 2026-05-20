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
 * **Waarom een aparte bean i.p.v. een private methode in [PublicatieStream]?**
 * CDI interceptors (`@Transactional`) werken alleen op aanroepen via een CDI-
 * proxy. Een directe in-class `this.verwerkEenClaim(...)` zou de
 * transactie-interceptor overslaan. Door de logica in een aparte
 * `@ApplicationScoped`-bean te plaatsen gaat de aanroep wél door de proxy.
 *
 * **Waarom `REQUIRES_NEW` per claim i.p.v. één transactie voor de hele batch?**
 * De HTTP-call naar de downstream kan tot 10s duren (timeout). Bij batch=50
 * en één trage downstream zou een batch-transactie >8 min open kunnen staan,
 * voorbij de typische `idle_in_transaction_session_timeout`. Postgres kapt
 * de transactie dan af → status-updates verdwijnen → outbox-rij blijft
 * `TE_PUBLICEREN` → duplicate downstream sends bij volgende poll. Per-claim
 * transactie houdt het lock kort vast (alleen tijdens de HTTP-call van dié
 * claim) en isoleert fouten — een NPE in claim N zet niet de status-updates
 * van claim 0..N-1 in dezelfde batch terug.
 *
 * **At-least-once delivery, geen exactly-once.** Tussen `downstreamClient.lever`
 * (succesvolle 2xx) en `commit` van `markeerGeslaagd` is een crash-venster waarin
 * de delivery wel afgeleverd maar nog niet als afgeleverd geregistreerd is. Bij
 * herstart wordt de claim opnieuw verzonden. Downstream-idempotency op
 * `(source, id)` is daarom verplicht; [CloudEventBuilder] levert daarvoor een
 * deterministische `id` per (berichtId, doel).
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
     * Cache van per-doel gestripte downstream-URLs. URLs zijn config-stabiel
     * (SmallRye Config rebindt geen properties at runtime — wijzigen alleen
     * bij redeploy of bean-restart) dus per (doel.key, raw-url) éénmaal
     * parsen + reconstrueren bespaart elke claim een URI-roundtrip.
     * `ConcurrentHashMap` omdat scheduler en eventueel andere CDI-callers
     * parallel kunnen lezen/schrijven.
     *
     * Cardinaliteit ≤ aantal geconfigureerde downstreams (typisch 2–5).
     * Bij hypothetische runtime config-rotatie groeit deze map monotoon —
     * accepteer wegens lage cardinaliteit, of vervang door size-bounded cache
     * als dat gedrag verandert.
     */
    private val gestripteDownstreamUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Begrenst de "doel niet meer in config"-warn tot één emit per
     * [ONBEKEND_DOEL_WARN_COOLDOWN] per [Publicatiedoel]. Zonder begrenzing
     * zou tijdens een config-removal-migratie elke claim, elke pollronde
     * een warn-regel produceren → log-storm.
     *
     * Sleutel = [Publicatiedoel] (value-class met `equals` op de wrapped key),
     * niet de rauwe `doel.key`-string: dat voorkomt dat een toekomstige
     * refactor per ongeluk een unrelated identifier doorgeeft.
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
                // Bericht weg tussen plan en verwerking — CASCADE op
                // `publicatie_deliveries.bericht_id` maakt deze tak in praktijk
                // onbereikbaar (delete bericht ruimt deliveries op vóór claim).
                // Vangnet voor handmatige DB-mutaties of toekomstige soft-delete.
                //
                // dataSubject is de ontvanger; die is hier niet meer beschikbaar
                // omdat het bericht ontbreekt. Vervang door berichtId zodat het
                // LDV-record auditbaar blijft (welke verwerkings-poging miste data?)
                // en geen lege subject-velden krijgt.
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

            // LDV-attributen: verwerkingsactiviteit "publiceren" met ontvanger
            // als dataSubject. URL van downstream als foreign_operation.processor
            // (via span-attribute, naast LogboekContext). Query/userinfo wordt
            // gestript zodat eventuele API-keys in URLs niet naar centrale tracing
            // lekken.
            val downstreamConfig = config.downstreams()[claim.doel.key]
            if (downstreamConfig == null && onbekendDoelWarnLimiter.magEmitten(claim.doel)) {
                // Config-drift: doel staat in outbox-rij maar niet meer in config.
                // Zonder log zou dit eindeloos retryen tegen `<onbekend>`-URL via
                // DownstreamClient.ConfiguratieFout. [LogStormLimiter] dempt de
                // warn tot 1× per cooldown-venster per doel zodat config-removal-
                // migratie geen log-storm produceert; ops krijgt nog steeds een
                // signaal om het lek te dichten vóór alle pogingen op zijn.
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
                        // 2xx ontvangen maar status niet bij te werken: duplicate-send
                        // venster bij volgende pollronde gegarandeerd. Operators moeten
                        // dit kunnen correleren met downstream-side duplicates.
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
                    val volgendePoging = RetryBeleid.volgendePoging(
                        nu = nu,
                        pogingenNaFout = claim.pogingen + 1,
                        maxPogingen = config.maxPogingen(),
                        basis = config.backoff().basis(),
                        plafond = config.backoff().plafond(),
                        claimId = claim.claimId,
                        herstelbaar = resultaat.herstelbaar,
                        retryAfter = resultaat.retryAfter,
                    )
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
            // LDV-context koppeling mag de transactie-commit nooit ondermijnen.
            // Een kapotte ProcessingHandler zou anders de status-writes terugrollen
            // → duplicate send. Smal vangen: brede `RuntimeException` zou
            // programmeerfouten (NPE, ConcurrentModificationException, IllegalStateException)
            // slikken; smal vangen op `IllegalArgumentException` (wat ProcessingHandler
            // zelf gooit op niet-URI/leeg processingActivityId — config-fout) laat de
            // rest doorvliegen zodat REQUIRES_NEW alsnog rolt en operators de bug zien.
            //
            // `span.end()` MOET altijd draaien (anders span-leak naar OTel-exporter),
            // ook als addLogboekContextToSpan een niet-IAE doorgooit. Daarom een
            // genest try/finally: outer borgt span.end(), inner saneert config-fout.
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
     * Verwijdert userinfo en query-string uit [url] zodat een eventuele API-key
     * niet als span-attribuut naar centrale tracing lekt. Path-segmenten worden
     * NIET gestript — wie path-tokens (`/secret-xyz/events`) als geheim
     * gebruikt schendt expliciet de aanbeveling om credentials uit URL-paths
     * te houden. Zie `docs/operator-handleiding.md` (sectie "Downstream-URL
     * conventies"); dezelfde caveat staat in [DownstreamClient.blokkeerIntern]'s KDoc.
     *
     * Bij parse-fout: log op warn met context (scheduler-thread mag niet stil
     * doorgaan zonder spoor) en vervang door marker. Smal `IllegalArgumentException`
     * i.p.v. `runCatching{}` zodat `OutOfMemoryError`/`StackOverflowError` niet
     * gemaskeerd worden.
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
         * Cooldown-venster voor de "doel niet meer in config"-warn.
         * 5 min ≈ 5 polling-rondes bij default 60s `magazijn.publicatie.polling.interval`:
         * kort genoeg dat ops binnen één deploy-window gewaarschuwd wordt,
         * lang genoeg om alle in-flight claims voor één doel te dempen tot
         * één regel per venster.
         *
         * Public-visible zodat tests deze constante kunnen importeren i.p.v.
         * de waarde te dupliceren.
         */
        val ONBEKEND_DOEL_WARN_COOLDOWN: java.time.Duration = java.time.Duration.ofMinutes(5)
    }
}
