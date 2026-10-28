package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.opentelemetry.api.trace.Span
import io.smallrye.mutiny.infrastructure.Infrastructure
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevraging
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenFout
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenGereed
import org.jboss.logging.Logger
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Samenvatting van een mislukte ophaalronde voor het logboek: het aantal mislukte organisaties of
 * de soort eindfout, nooit een foutmelding. De ERROR-child draagt de betrokkene en gaat bij een
 * inzageverzoek naar buiten, dus een melding uit de keten mag er via geen enkele weg in komen.
 */
class OphaalrondeMislukt(samenvatting: String) : RuntimeException(samenvatting, null, false, false)

/**
 * Of een event de ophaalronde als mislukt afsluit, en zo ja met welke samenvatting. Alleen het
 * slotevent telt; tussentijdse events leveren `null`. De `when` is uitputtend over de sealed
 * hiërarchie: een nieuw soort voortgangsbericht dwingt hier een bewuste keuze af.
 *
 * `nietOpgehaald` telt bewust niet mee. Die organisaties zijn niet bevraagd omdat de uitvraag zelf
 * te veel werk tegelijk had; de verwerking is dan goed verlopen, alleen over minder organisaties.
 * Wat de ondernemer wél en niet kreeg, staat in de tellers van het slotevent.
 */
internal fun mislukkingVan(event: MagazijnEvent): OphaalrondeMislukt? = when (event) {
    is OphalenGereed ->
        if (event.mislukt == 0) null else OphaalrondeMislukt("${event.mislukt} van ${event.totaalMagazijnen} organisaties mislukt")
    is OphalenFout -> OphaalrondeMislukt(event.javaClass.simpleName)
    is MagazijnBevraging -> null
}

/**
 * De logregel die de `@Logboek`-interceptor voor de lopende actie gaat wegschrijven. Een afnemer die
 * alleen de `SpanContext` heeft, bouwt die verwijzing zelf; een latere uitkomst komt er dan als
 * child onder. Aanroepen binnen de methode, nadat de betrokkene gezet is.
 */
internal fun logregelVanDezeActie(logboekContext: LogboekContext, naam: String, processingActivityId: String): Logregel =
    Logregel(Span.current().spanContext, naam, processingActivityId, logboekContext.effectiveSubjects().singleOrNull())

/**
 * Legt vast dat een ophaalronde mislukte nadat haar logregel al geschreven was. `_ophalen` geeft
 * een stream terug, en de interceptor schrijft de logregel weg zodra de methode terugkeert — vóór
 * het eerste event. Die logregel staat dus op `UNSET` en blijft zo; een mislukte uitkomst komt er
 * als ERROR-child onder ([ProcessingHandler.recordFailedOutcome]), dezelfde vorm als in het
 * magazijn. Leesregel voor een gestarte ronde: een logregel op `UNSET` zonder ERROR-child is geslaagd.
 *
 * Op [executor] en niet op de aanroepende thread: het slotevent ontstaat ná een reactieve
 * Redis-schrijfactie, op de event-loop, en het vastleggen schrijft synchroon naar de database.
 */
class OphaalUitkomst(
    private val processingHandler: ProcessingHandler,
    private val executor: Executor = Infrastructure.getDefaultWorkerPool(),
) {

    private val log = Logger.getLogger(OphaalUitkomst::class.java)

    /**
     * Gooit niet: de ophaalronde zelf hoort er niet door te veranderen. [kenmerken] identificeert de
     * verwerking in de applicatielog als de uitkomst verloren gaat; zonder persoonsgegevens.
     */
    fun legVast(logregel: Logregel, mislukking: Throwable, kenmerken: String) {
        try {
            executor.execute { schrijf(logregel, mislukking, kenmerken) }
        } catch (ex: RejectedExecutionException) {
            log.errorf("%s: vastleggen niet ingepland, %s (%s)", ALERT_TOKEN, ex.javaClass.name, kenmerken)
        }
    }

    private fun schrijf(logregel: Logregel, mislukking: Throwable, kenmerken: String) {
        val verloren = try {
            processingHandler.recordFailedOutcome(logregel, mislukking)
        } catch (ex: Throwable) {
            if (ex is InterruptedException) Thread.currentThread().interrupt()

            // Bij een LinkageError is de message de ontbrekende signatuur: nodig voor diagnose,
            // en zonder persoonsgegevens. Andere messages kunnen die wel bevatten.
            val detail = if (ex is LinkageError) " (${ex.message})" else ""
            log.errorf("%s: vastleggen gaf %s%s (%s)", ALERT_TOKEN, ex.javaClass.name, detail, kenmerken)

            return
        }

        if (verloren.isEmpty()) return

        // De wrapper logt dit ook, maar zonder token waarop een alert kan routeren.
        log.errorf(
            "%s: uitkomst niet in het logboek vastgelegd; de ophaalronde leest als geslaagd (%s) [%s]",
            ALERT_TOKEN,
            kenmerken,
            verloren.joinToString { "${it.spanContext.traceId}:${it.spanContext.spanId}" },
        )
    }

    companion object {
        /** Hetzelfde token als in het magazijn, zodat één alert beide diensten dekt. */
        const val ALERT_TOKEN = "LDV_UITKOMST_ONTBREEKT"
    }
}
