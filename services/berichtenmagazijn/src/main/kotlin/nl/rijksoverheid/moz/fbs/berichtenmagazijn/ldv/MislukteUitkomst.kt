package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import org.jboss.logging.Logger

/**
 * Legt vast dat een verwerking mislukte nadat haar logregel al bevestigd was.
 *
 * Aanleveren en publiceren schrijven de logregel vóór de verwerking, zodat er nooit een
 * verwerking zonder logregel plaatsvindt. Die logregel is daarna definitief en staat op
 * `UNSET`, wat de standaard leest als "afgerond zonder systeemfout". Een mislukte uitkomst
 * komt daarom als ERROR-child onder die logregel ([ProcessingHandler.recordFailedOutcome]).
 * Leesregel voor het logboek: een logregel zonder ERROR-child is geslaagd.
 */
class MislukteUitkomst(private val processingHandler: ProcessingHandler) {

    private val log = Logger.getLogger(MislukteUitkomst::class.java)

    /**
     * Schrijft de ERROR-children voor [logregels]. Gooit niet: de fout die de verwerking
     * liet mislukken moet de aanroeper bereiken, niet een logboekfout er overheen.
     *
     * [fout] komt ongefilterd in het logboek, op rijen die de betrokkene dragen en bij een
     * inzageverzoek naar buiten gaan; geef dus een samenvatting zonder persoonsgegevens mee.
     */
    fun legVast(logregels: List<Logregel>, fout: Throwable) {
        val zonderUitkomst = processingHandler.recordFailedOutcome(logregels, fout)

        if (zonderUitkomst.isEmpty()) return

        // Onder-rapportage: zonder ERROR-child leest de logregel als geslaagd. De wrapper
        // logt dit ook, maar zonder token waarop een alert kan routeren.
        log.errorf(
            "%s: uitkomst van %d logregel(s) niet in het logboek vastgelegd; die lezen als geslaagd [%s]",
            ALERT_TOKEN,
            zonderUitkomst.size,
            zonderUitkomst.joinToString { "${it.spanContext.traceId}:${it.spanContext.spanId}" },
        )
    }

    companion object {
        /** Stabiel token voor alert-routing op een verloren uitkomst-logregel. */
        const val ALERT_TOKEN = "LDV_UITKOMST_ONTBREEKT"
    }
}
