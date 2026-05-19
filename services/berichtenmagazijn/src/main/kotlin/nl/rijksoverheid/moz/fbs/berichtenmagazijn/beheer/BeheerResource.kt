package nl.rijksoverheid.moz.fbs.berichtenmagazijn.beheer

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ProcessingActivities
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.BeheerApi
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal.BerichtDtoMapper
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import java.util.UUID
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtStatusPatch as BerichtStatusPatchDto
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusPatch as BerichtStatusPatchDomain

/**
 * Resource voor de Beheer-API: status-PATCH en soft-delete. Implementeert de
 * gegenereerde [BeheerApi] interface en delegeert mutaties aan
 * [BerichtBeheerService]; de response-bodies worden via [BerichtDtoMapper]
 * uniform met de Ophaal-API gemapt.
 */
@Path(ApiInfo.BASE_PATH + "/berichten/{berichtId}")
@ApplicationScoped
class BeheerResource(
    private val beheerService: BerichtBeheerService,
    private val logboekContext: LogboekContext,
    @param:Context private val uriInfo: UriInfo,
) : BeheerApi {

    @Logboek(
        name = "bijwerken-bericht-status",
        processingActivityId = ProcessingActivities.MAGAZIJN_BEHEER,
    )
    override fun updateBerichtStatus(
        berichtId: UUID,
        xOntvanger: String,
        berichtStatusPatch: BerichtStatusPatchDto,
    ): Bericht {
        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        registreerLdvSubject(ontvanger)
        val bericht = beheerService.wijzigStatus(
            berichtId = berichtId,
            ontvanger = ontvanger,
            patch = BerichtStatusPatchDomain(
                gelezen = berichtStatusPatch.gelezen,
                map = berichtStatusPatch.map,
            ),
        )
        return BerichtDtoMapper.toBericht(bericht, uriInfo.baseUriBuilder)
    }

    @Logboek(
        name = "verwijderen-bericht",
        processingActivityId = ProcessingActivities.MAGAZIJN_BEHEER,
    )
    override fun verwijderBericht(berichtId: UUID, xOntvanger: String) {
        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        registreerLdvSubject(ontvanger)
        beheerService.verwijder(berichtId, ontvanger)
    }

    private fun registreerLdvSubject(ontvanger: Identificatienummer) {
        // dataSubjectType is identifier-type (BSN/RSIN/KVK/OIN) per Logboek-spec.
        logboekContext.dataSubjectId = ontvanger.waarde
        logboekContext.dataSubjectType = ontvanger.type.name
    }
}
