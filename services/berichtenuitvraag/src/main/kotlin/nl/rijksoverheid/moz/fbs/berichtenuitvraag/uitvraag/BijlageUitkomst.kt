package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.opentelemetry.api.trace.Span
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.common.LdvFoutSamenvatting
import org.jboss.logging.Logger

/**
 * Legt vast dat het doorgeven van een bijlage mislukte nadat de logregel al geschreven was.
 *
 * De logregel van `getBijlage` ontstaat als de methode terugkeert, en dan is alleen het begin
 * van de bijlage gelezen; de rest streamt daarna door. Breekt het magazijn die rest af, dan
 * staat de logregel al op `UNSET`, wat de standaard leest als "afgerond zonder systeemfout". De
 * mislukte uitkomst komt daarom als ERROR-child onder die logregel
 * ([ProcessingHandler.recordFailedOutcome]), net als in het magazijn. Leesregel: een logregel op
 * `UNSET` zonder ERROR-child is correct afgerond.
 *
 * Alleen het fouttype gaat het logboek in ([LdvFoutSamenvatting]): de ERROR-child draagt de
 * betrokkene en gaat bij een inzageverzoek naar buiten.
 */
class BijlageUitkomst(private val processingHandler: ProcessingHandler) {

    /**
     * Gooit niet: de fout die de overdracht liet mislukken moet de aanroeper bereiken. [kenmerken]
     * identificeert de verwerking in de applicatielog als de uitkomst verloren gaat, en bevat
     * daarom geen persoonsgegevens.
     */
    fun legMislukkingVast(logregel: Logregel, oorzaak: Throwable, kenmerken: String) {
        val zonderUitkomst = try {
            processingHandler.recordFailedOutcome(listOf(logregel), LdvFoutSamenvatting.van(oorzaak))
        } catch (ex: Throwable) {
            if (ex is InterruptedException) Thread.currentThread().interrupt()

            // Bij een LinkageError is de message de ontbrekende signatuur: nodig voor diagnose,
            // en zonder persoonsgegevens. Andere messages kunnen die wel bevatten.
            val detail = if (ex is LinkageError) " (${ex.message})" else ""
            log.errorf("%s: vastleggen gaf %s%s (%s)", ALERT_TOKEN, ex.javaClass.name, detail, kenmerken)
            listOf(logregel)
        }

        if (zonderUitkomst.isEmpty()) return

        // Onder-rapportage: zonder ERROR-child leest de logregel als geslaagd. De wrapper logt dit
        // ook, maar zonder token waarop een alert kan routeren.
        log.errorf(
            "%s: uitkomst niet in het logboek vastgelegd; de logregel leest als geslaagd (%s) [%s:%s]",
            ALERT_TOKEN,
            kenmerken,
            logregel.spanContext.traceId,
            logregel.spanContext.spanId,
        )
    }

    companion object {
        /** Stabiel token voor alert-routing op een verloren uitkomst-logregel; gelijk aan dat van het magazijn. */
        const val ALERT_TOKEN = "LDV_UITKOMST_ONTBREEKT"

        private val log: Logger = Logger.getLogger(BijlageUitkomst::class.java)

        /**
         * De logregel van de lopende `@Logboek`-methode. De interceptor houdt zijn span actief zolang
         * de methode loopt, dus dit moet binnen de methode gebeuren en niet in de writer erna.
         */
        fun logregelVanDezeActie(logboekContext: LogboekContext, naam: String, processingActivityId: String) =
            Logregel(Span.current().spanContext, naam, processingActivityId, logboekContext.effectiveSubjects().singleOrNull())
    }
}
