package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.AanleverResource
import nl.rijksoverheid.moz.fbs.common.exception.UncaughtExceptionMapper

/**
 * Tellers naast de alertregels over het logboek, zodat een melding op een getal kan steunen in
 * plaats van op een zoekopdracht in de log. Het label `code` is het token uit de logregel; alleen de
 * waarden van [Waarschuwing], dus begrensd.
 *
 * De globale registry, omdat [MislukteUitkomst] geen bean is. Quarkus hangt zijn eigen registry
 * daaronder, dus de tellers komen gewoon op `/q/metrics`.
 */
object LogboekMeting {

    enum class Waarschuwing(val code: String) {
        UITKOMST_ONTBREEKT(MislukteUitkomst.ALERT_TOKEN),
        OPSLAG_ONZEKER(AanleverResource.OPSLAG_ONZEKER_ALERT_TOKEN),
    }

    fun waarschuwing(soort: Waarschuwing, registry: MeterRegistry = Metrics.globalRegistry) {
        waarschuwingTeller(soort, registry).increment()
    }

    fun geweigerd(registry: MeterRegistry = Metrics.globalRegistry) {
        geweigerdTeller(registry).increment()
    }

    internal fun waarschuwingTeller(soort: Waarschuwing, registry: MeterRegistry): Counter =
        Counter.builder("fbs.logboek.waarschuwing")
            .description("Gevallen waarin het logboek een verwerking mogelijk onjuist weergeeft, per alertcode")
            .tag("code", soort.code)
            .register(registry)

    internal fun geweigerdTeller(registry: MeterRegistry): Counter =
        Counter.builder("fbs.logboek.geweigerd")
            .description("Verzoeken geweigerd omdat het logboek de verwerking niet kon vastleggen")
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
 * Een verzoek dat het logboek tegenhield, krijgt hetzelfde antwoord als elke onverwachte fout; alleen
 * de teller komt erbij. Zo blijft het foutcontract op één plek, in [UncaughtExceptionMapper].
 */
@Provider
class LogboekSchrijffoutMapper : ExceptionMapper<LogboekWriteException> {

    private val vangnet = UncaughtExceptionMapper()

    override fun toResponse(exception: LogboekWriteException): Response {
        LogboekMeting.geweigerd()

        return vangnet.toResponse(exception)
    }
}
