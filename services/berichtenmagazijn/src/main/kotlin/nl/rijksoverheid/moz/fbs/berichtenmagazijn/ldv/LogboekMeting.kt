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
import nl.mijnoverheidzakelijk.ldv.repository.SpanStorageException
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

    private val vangnet = UncaughtExceptionMapper()

    override fun toResponse(exception: LogboekWriteException): Response {
        LogboekMeting.geweigerd()

        return vangnet.toResponse(exception)
    }
}

/** Zie [LogboekSchrijffoutMapper]. */
@Provider
class LogboekOpslagfoutMapper : ExceptionMapper<SpanStorageException> {

    private val vangnet = UncaughtExceptionMapper()

    override fun toResponse(exception: SpanStorageException): Response {
        LogboekMeting.geweigerd()

        return vangnet.toResponse(exception)
    }
}
