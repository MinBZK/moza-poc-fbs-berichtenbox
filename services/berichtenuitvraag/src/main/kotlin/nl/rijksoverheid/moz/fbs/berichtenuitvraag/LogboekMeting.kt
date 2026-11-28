package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import io.quarkus.runtime.StartupEvent
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException
import nl.mijnoverheidzakelijk.ldv.repository.SpanStorageException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheMeting
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie.SessieLogboek
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie.SessieVeger
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.OphaalUitkomst
import nl.rijksoverheid.moz.fbs.common.exception.UncaughtExceptionMapper
import org.jboss.logging.Logger

/**
 * Tellers naast de alertregels over het logboek, zodat een melding op een getal kan steunen in
 * plaats van op een zoekopdracht in de log. Het label `code` is het token uit de logregel; alleen de
 * waarden van [Waarschuwing], dus begrensd.
 *
 * De globale registry, omdat [OphaalUitkomst] geen bean is. Quarkus hangt zijn eigen registry
 * daaronder, dus de tellers komen gewoon op `/q/metrics`.
 */
object LogboekMeting {

    enum class Waarschuwing(val code: String) {
        UITKOMST_ONTBREEKT(OphaalUitkomst.ALERT_TOKEN),
        OPRUIMEN_ONTBREEKT(SessieLogboek.ALERT_TOKEN),
        ZONDER_LOGREGEL(ZONDER_LOGREGEL_TOKEN),

        // Geen LDV-token, wel dezelfde afwijking: zonder veegtaak of na een mislukte opruiming wist
        // de Redis-TTL gegevens zonder logregel.
        SESSIE_VEGER_FAALT(SessieVeger.METING_CODE),
        OPRUIMEN_NA_SCHRIJVEN(SessiecacheMeting.OPRUIMEN_NA_SCHRIJVEN),
    }

    fun waarschuwing(soort: Waarschuwing, registry: MeterRegistry = Metrics.globalRegistry) {
        waarschuwingTeller(soort, registry).increment()
    }

    fun geweigerd(registry: MeterRegistry = Metrics.globalRegistry) {
        geweigerdTeller(registry).increment()
    }

    /**
     * Een lezend verzoek, of een dat het logboek vóór de verwerking schrijft, is geweigerd: er is
     * niets gebeurd. Bij elk ander wijzigend verzoek schrijft de wrapper pas ná de methode; dan is de
     * wijziging doorgevoerd zonder logregel, en dat is een afwijking die gemeld moet worden.
     */
    fun schrijffout(methode: String, pad: String, registry: MeterRegistry = Metrics.globalRegistry) {
        if (methode in LEZEND || "$methode $pad" in VOORAF_VASTGELEGD) {
            geweigerd(registry)
        } else {
            log.errorf(
                "%s: verwerking doorgevoerd zonder logregel in het logboek (methode=%s, pad=%s)",
                ZONDER_LOGREGEL_TOKEN,
                methode,
                pad,
            )
            waarschuwing(Waarschuwing.ZONDER_LOGREGEL, registry)
        }
    }

    const val ZONDER_LOGREGEL_TOKEN = "LDV_ZONDER_LOGREGEL"

    private val LEZEND = setOf("GET", "HEAD", "OPTIONS")

    /** In deze dienst schrijft geen enkel verzoek het logboek vóór de verwerking. */
    private val VOORAF_VASTGELEGD = emptySet<String>()

    private val log = Logger.getLogger(LogboekMeting::class.java)

    internal fun waarschuwingTeller(soort: Waarschuwing, registry: MeterRegistry): Counter =
        Counter.builder("fbs.logboek.waarschuwing")
            .description("Gevallen waarin het logboek een verwerking mogelijk onjuist weergeeft, per alertcode")
            .tag("code", soort.code)
            .register(registry)

    internal fun geweigerdTeller(registry: MeterRegistry): Counter =
        Counter.builder("fbs.logboek.geweigerd")
            .description("Verzoeken met een 500 omdat het logboek de verwerking niet kon vastleggen")
            .register(registry)
}

/** Zet elke teller vanaf de start op 0: een reeks die pas bij het eerste geval verschijnt, mist `increase()`. */
@ApplicationScoped
class LogboekMetingStart {

    fun registreer(@Observes start: StartupEvent, registry: MeterRegistry) {
        LogboekMeting.Waarschuwing.entries.forEach { LogboekMeting.waarschuwingTeller(it, registry) }
        LogboekMeting.geweigerdTeller(registry)
    }
}

/**
 * Een verzoek waarvan het logboek de verwerking niet kon vastleggen, krijgt hetzelfde antwoord als
 * elke onverwachte fout; alleen de teller komt erbij. Zo blijft het foutcontract op één plek, in
 * [UncaughtExceptionMapper].
 *
 * Niet elk geval is een weigering: de wrapper schrijft bij een `@Logboek`-methode pas ná de methode.
 * Bij een wijzigend verzoek is de wijziging dan al doorgevoerd, zonder logregel.
 *
 * Twee types, want de wrapper meldt een schrijffout op twee manieren: als [LogboekWriteException] bij
 * de bevestiging, en als [SpanStorageException] als de opslag al bij het afsluiten van de span faalt.
 */
@Provider
class LogboekSchrijffoutMapper : ExceptionMapper<LogboekWriteException> {

    @Inject
    lateinit var verzoek: RoutingContext

    private val vangnet = UncaughtExceptionMapper()

    override fun toResponse(exception: LogboekWriteException): Response {
        LogboekMeting.schrijffout(verzoek.request().method().name(), verzoek.normalizedPath())

        return vangnet.toResponse(exception)
    }
}

/** Zie [LogboekSchrijffoutMapper]. */
@Provider
class LogboekOpslagfoutMapper : ExceptionMapper<SpanStorageException> {

    @Inject
    lateinit var verzoek: RoutingContext

    private val vangnet = UncaughtExceptionMapper()

    override fun toResponse(exception: SpanStorageException): Response {
        LogboekMeting.schrijffout(verzoek.request().method().name(), verzoek.normalizedPath())

        return vangnet.toResponse(exception)
    }
}
