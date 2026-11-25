package nl.rijksoverheid.moz.fbs.berichtenmagazijn.logboek

import jakarta.enterprise.context.ApplicationScoped
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ProcessingActivities
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.LogboekApi
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.LogboekVerwerking
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.LogboekVerwerkingen
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.time.Instant
import java.util.UUID
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Identificatienummer as IdentificatienummerDto

/**
 * Inzage in het logboek van één betrokkene. Geeft persoonsgegevens terug en is niet
 * afgeschermd: alleen bedoeld voor de demonstratie met fictieve betrokkenen.
 */
@ApplicationScoped
class LogboekResource(
    private val lezer: LogboekLezer,
    private val logboekContext: LogboekContext,
) : LogboekApi {

    @Logboek(
        name = "inzage-logboek",
        processingActivityId = ProcessingActivities.MAGAZIJN_INZAGE,
    )
    override fun getLogboekVerwerkingen(xBetrokkene: String, van: Instant?, tot: Instant?): LogboekVerwerkingen {
        val betrokkene = Identificatienummer.fromHeader(xBetrokkene)
        logboekContext.dataSubjectId = betrokkene.waarde
        logboekContext.dataSubjectType = betrokkene.type.name
        val verwerkingen = Leesregel.pasToe(lezer.lees(betrokkene, van, tot))

        return LogboekVerwerkingen().apply {
            this.betrokkene = IdentificatienummerDto().apply {
                type = IdentificatienummerDto.TypeEnum.valueOf(betrokkene.type.name)
                waarde = betrokkene.waarde
            }
            this.verwerkingen = verwerkingen.map(::naarDto)
        }
    }

    private fun naarDto(verwerking: Verwerking) = LogboekVerwerking().apply {
        tijdstip = verwerking.tijdstip
        handeling = verwerking.handeling
        verwerkingsactiviteit = verwerking.verwerkingsactiviteit
        uitkomst = when (verwerking.uitkomst) {
            Uitkomst.GESLAAGD -> LogboekVerwerking.UitkomstEnum.GESLAAGD
            Uitkomst.MISLUKT -> LogboekVerwerking.UitkomstEnum.MISLUKT
        }
        berichtId = verwerking.berichtId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        verstrektAan = verwerking.verstrektAan
        poging = verwerking.poging
        foutcategorie = verwerking.foutcategorie
    }
}
