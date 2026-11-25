package nl.rijksoverheid.moz.fbs.common.logboek

import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import java.time.Instant

/** Eén rij uit de logboektabel, met alleen de attributen die de inzage gebruikt. */
data class LogboekRij(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val status: String,
    val naam: String,
    val start: Instant,
    val activiteit: String?,
    val subjectType: String?,
    val subjectId: String?,
    val uitkomstAttribuut: String?,
    val berichtId: String?,
    val doel: String?,
    val poging: Long?,
    val publicatieFout: String?,
    val foutType: String?,
    val foutMessage: String?,
)

enum class Uitkomst { GESLAAGD, MISLUKT }

data class Verwerking(
    val tijdstip: Instant,
    val handeling: String,
    val verwerkingsactiviteit: String?,
    val uitkomst: Uitkomst,
    val berichtId: String?,
    val verstrektAan: String?,
    val poging: Long?,
    val foutcategorie: String?,
)

/**
 * Past de leesregel van het logboek toe: een logregel op `UNSET` zonder ERROR-child is
 * geslaagd; een logregel op `ERROR`, of met een ERROR-child, is mislukt. Een ERROR-child
 * (herkenbaar aan het uitkomst-attribuut) is zelf geen verwerking: hij hangt via trace en
 * `parent_span_id` onder zijn logregel.
 */
object Leesregel {

    private const val BERICHT_ID_ONLY = "BERICHT_ID_ONLY"

    fun pasToe(rijen: List<LogboekRij>): List<Verwerking> {
        val (uitkomsten, logregels) = rijen.partition { it.uitkomstAttribuut != null }
        val mislukteUitkomsten = uitkomsten
            .filter { it.uitkomstAttribuut == ProcessingHandler.OUTCOME_FAILED }
            .associateBy { it.traceId to it.parentSpanId }

        val gekoppeld = logregels.map { logregel ->
            naarVerwerking(logregel, mislukteUitkomsten[logregel.traceId to logregel.spanId])
        }
        // Een uitkomst zonder logregel in het resultaat (bv. buiten de gevraagde periode) blijft
        // zichtbaar als mislukte verwerking, zodat de inzage niets weglaat.
        val bekendeLogregels = logregels.map { it.traceId to it.spanId }.toSet()
        val los = mislukteUitkomsten
            .filterKeys { it !in bekendeLogregels }
            .values
            .map { naarVerwerking(it, it) }

        return (gekoppeld + los).sortedByDescending { it.tijdstip }
    }

    private fun naarVerwerking(logregel: LogboekRij, mislukteUitkomst: LogboekRij?): Verwerking {
        val mislukt = mislukteUitkomst != null || logregel.status == "ERROR"
        // De ruwe exception.message van een logregel zelf kan gegevens uit het verzoek
        // bevatten; alleen die van een ERROR-child is een vaste samenvatting.
        val foutcategorie = when {
            !mislukt -> null
            mislukteUitkomst != null -> mislukteUitkomst.foutMessage ?: mislukteUitkomst.foutType
            else -> logregel.publicatieFout ?: logregel.foutType
        }
        val berichtId = logregel.berichtId
            ?: logregel.subjectId.takeIf { logregel.subjectType == BERICHT_ID_ONLY }

        return Verwerking(
            tijdstip = logregel.start,
            handeling = logregel.naam,
            verwerkingsactiviteit = logregel.activiteit,
            uitkomst = if (mislukt) Uitkomst.MISLUKT else Uitkomst.GESLAAGD,
            berichtId = berichtId,
            verstrektAan = logregel.doel,
            poging = logregel.poging,
            foutcategorie = foutcategorie,
        )
    }
}
