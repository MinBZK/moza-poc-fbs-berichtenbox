package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import jakarta.enterprise.context.ApplicationScoped
import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.OpruimUitkomst
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.jboss.logging.Logger

/** Waarom de berichtgegevens van een ontvanger gewist werden; de waarde komt zo in het logboek. */
enum class OpruimAanleiding(val waarde: String) {
    UITGELOGD("uitgelogd"),
    VERLOPEN("verlopen"),
    NIEUWE_START("nieuwe-start"),
}

/**
 * Legt in het logboek vast dat de berichtgegevens van een ontvanger gewist zijn, als eigen logregel
 * naast die van het verzoek dat het wissen veroorzaakte.
 *
 * Zonder `@Logboek`-interceptor: die leest de trace-context uit de HTTP-headers en faalt buiten een
 * verzoek, terwijl het opruimen van verlopen sessies juist buiten een verzoek gebeurt. Binnen een
 * verzoek wordt de regel een kind van de logregel van dat verzoek ([Context.current]); de
 * request-scoped [LogboekContext] blijft onaangeroerd, zodat de logregel van het verzoek zelf zijn
 * eigen naam en activiteit houdt.
 *
 * Gooit niet als het schrijven mislukt. Het wissen is dan al gebeurd en valt niet terug te draaien;
 * een fout zou alleen het verzoek laten mislukken dat het wissen veroorzaakte. In plaats daarvan
 * een foutregel met [ALERT_TOKEN], zonder ontvanger. Alleen een Error gaat door, na diezelfde regel.
 */
@ApplicationScoped
class SessieLogboek(private val processingHandler: ProcessingHandler) {

    private val log = Logger.getLogger(SessieLogboek::class.java)

    fun opgeruimd(
        ontvanger: Identificatienummer,
        aanleiding: OpruimAanleiding,
        uitkomst: OpruimUitkomst = OpruimUitkomst.GEWIST,
    ) {
        var afgehandeld = false

        try {
            schrijf(ontvanger, aanleiding, uitkomst)
            afgehandeld = true
        } catch (e: Exception) {
            afgehandeld = true
            meldOntbrekend(ontvanger, aanleiding, uitkomst, e.javaClass.name)
        } finally {
            // Een Error (zoals een NoClassDefFoundError uit de exporter) gaat hier bewust door, maar
            // niet zonder het alert: anders ontbreekt de logregel zonder dat iemand het ziet.
            if (!afgehandeld) meldOntbrekend(ontvanger, aanleiding, uitkomst, "onverwachte Error")
        }
    }

    private fun meldOntbrekend(ontvanger: Identificatienummer, aanleiding: OpruimAanleiding, uitkomst: OpruimUitkomst, fout: String) {
        log.errorf(
            "%s: opruimen (aanleiding=%s, uitkomst=%s, ontvanger.type=%s) niet in het logboek vastgelegd: %s",
            ALERT_TOKEN,
            aanleiding.waarde,
            uitkomst,
            ontvanger.type,
            fout,
        )
    }

    private fun schrijf(ontvanger: Identificatienummer, aanleiding: OpruimAanleiding, uitkomst: OpruimUitkomst) {
        // Thread-gebonden: een schrijffout van een eerdere verwerking op deze thread is niet de onze.
        LogboekWriteFailureRecorder.clear()

        val span = processingHandler.startSpan(NAAM, Context.current())

        try {
            span.setAttribute(AANLEIDING_ATTRIBUUT, aanleiding.waarde)
            span.setAttribute(UITKOMST_ATTRIBUUT, uitkomst.name.lowercase())

            val context = LogboekContext().apply {
                processingActivityId = ProcessingActivities.UITVRAAG_SESSIE
                actionName = NAAM
                dataSubjectType = ontvanger.type.name
                dataSubjectId = ontvanger.waarde
                // Alleen GEWIST is zeker gebeurd; bij MISLUKT en ONBEVESTIGD hoort de regel niet als geslaagd te lezen.
                status = if (uitkomst == OpruimUitkomst.GEWIST) StatusCode.OK else StatusCode.ERROR
            }

            processingHandler.addLogboekContextToSpan(span, context)
        } finally {
            span.end()
            processingHandler.enforceWriteAcknowledgement(true)
        }
    }

    companion object {
        const val NAAM = "uitvraag-sessie-opruimen"
        const val AANLEIDING_ATTRIBUUT = "fbs.sessie.opruimen.aanleiding"
        const val UITKOMST_ATTRIBUUT = "fbs.sessie.opruimen.uitkomst"

        /** Stabiel token voor alert-routing op een wissen zonder logregel. */
        const val ALERT_TOKEN = "LDV_OPRUIMEN_ONTBREEKT"
    }
}
