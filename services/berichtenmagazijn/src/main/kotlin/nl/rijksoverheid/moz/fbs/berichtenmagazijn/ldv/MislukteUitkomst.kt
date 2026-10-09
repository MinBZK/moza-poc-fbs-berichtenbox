package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.DownstreamResultaat
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.LeveringMislukt
import nl.rijksoverheid.moz.fbs.common.LdvFoutSamenvatting
import org.jboss.logging.Logger

/**
 * Legt vast dat een verwerking mislukte nadat haar logregel al bevestigd was.
 *
 * Aanleveren en publiceren schrijven de logregel vóór de verwerking, zodat er nooit een
 * verwerking zonder logregel plaatsvindt. Die logregel is daarna definitief en staat op
 * `UNSET`, wat de standaard leest als "afgerond zonder systeemfout". Een mislukte uitkomst
 * komt daarom als ERROR-child onder die logregel ([ProcessingHandler.recordFailedOutcome]).
 * Leesregel: een logregel op `UNSET` zonder ERROR-child is geslaagd.
 *
 * De fout wordt hier samengevat en niet door de aanroeper: de ERROR-child draagt de
 * betrokkene en gaat bij een inzageverzoek naar buiten, dus een ruwe exceptie-message
 * (`Failing row contains (…)`) mag er via geen enkele aanroep in komen.
 *
 * [kenmerken] in beide methodes identificeert de verwerking in de applicatielog wanneer de
 * uitkomst verloren gaat; alleen gegevens zonder persoonsgegevens (`berichtId`, doel).
 */
class MislukteUitkomst(private val processingHandler: ProcessingHandler) {

    private val log = Logger.getLogger(MislukteUitkomst::class.java)

    /**
     * Voor een fout uit de verwerking zelf; alleen het type gaat het logboek in. Schrijft
     * altijd: de aanroeper moet hebben vastgesteld dat de verwerking zeker niet plaatsvond.
     */
    fun legZekereFoutVast(logregels: List<Logregel>, oorzaak: Throwable, kenmerken: String) {
        schrijf(logregels, LdvFoutSamenvatting.van(oorzaak), kenmerken)
    }

    /**
     * Voor een mislukte levering; alleen de categorie gaat het logboek in. Schrijft niets als
     * de afnemer het bericht mogelijk wél kreeg: een ERROR-child zou dan "niet verstrekt"
     * melden, en te weinig registreren is erger dan te veel.
     */
    fun legLeveringVast(logregels: List<Logregel>, resultaat: DownstreamResultaat.Mislukt, kenmerken: String) {
        val nietVerstrekt = LeveringMislukt.van(resultaat) ?: return

        schrijf(logregels, nietVerstrekt, kenmerken)
    }

    /**
     * Gooit niet: de fout die de verwerking liet mislukken moet de aanroeper bereiken. De
     * wrapper belooft dat ook, maar een afwijkende wrapper-versie op het classpath zou dat
     * contract ongemerkt breken.
     */
    private fun schrijf(logregels: List<Logregel>, samenvatting: Throwable, kenmerken: String) {
        if (logregels.isEmpty()) return

        val zonderUitkomst = try {
            processingHandler.recordFailedOutcome(logregels, samenvatting)
        } catch (ex: Throwable) {
            if (ex is InterruptedException) Thread.currentThread().interrupt()

            // Bij een LinkageError is de message de ontbrekende signatuur: nodig voor diagnose,
            // en zonder persoonsgegevens. Andere messages kunnen die wel bevatten.
            val detail = if (ex is LinkageError) " (${ex.message})" else ""
            log.errorf("%s: vastleggen gaf %s%s (%s)", ALERT_TOKEN, ex.javaClass.name, detail, kenmerken)
            logregels
        }

        if (zonderUitkomst.isEmpty()) return

        // Onder-rapportage: zonder ERROR-child leest de logregel als geslaagd. De wrapper
        // logt dit ook, maar zonder token waarop een alert kan routeren.
        log.errorf(
            "%s: uitkomst van %d logregel(s) niet in het logboek vastgelegd; die lezen als geslaagd (%s) [%s]",
            ALERT_TOKEN,
            zonderUitkomst.size,
            kenmerken,
            zonderUitkomst.joinToString { "${it.spanContext.traceId}:${it.spanContext.spanId}" },
        )
    }

    companion object {
        /** Stabiel token voor alert-routing op een verloren uitkomst-logregel. */
        const val ALERT_TOKEN = "LDV_UITKOMST_ONTBREEKT"
    }
}
