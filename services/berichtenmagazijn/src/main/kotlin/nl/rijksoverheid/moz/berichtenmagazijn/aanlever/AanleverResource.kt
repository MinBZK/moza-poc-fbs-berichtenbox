package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.berichtenmagazijn.api.AanleverApi
import nl.rijksoverheid.moz.berichtenmagazijn.api.model.AanleverBerichtRequest
import nl.rijksoverheid.moz.berichtenmagazijn.api.model.BerichtLinks
import nl.rijksoverheid.moz.berichtenmagazijn.api.model.BerichtResponse
import nl.rijksoverheid.moz.berichtenmagazijn.api.model.Link

@Path("/api/v1/berichten")
@ApplicationScoped
class AanleverResource(
    private val opslagService: BerichtOpslagService,
) : AanleverApi {

    @Inject
    lateinit var logboekContext: LogboekContext

    @Context
    lateinit var uriInfo: UriInfo

    @Logboek(
        name = "aanleveren-bericht",
        processingActivityId = "https://register.example.com/verwerkingen/berichtenmagazijn-aanleveren",
    )
    override fun aanleverBericht(aanleverBerichtRequest: AanleverBerichtRequest): BerichtResponse {
        logboekContext.dataSubjectId = aanleverBerichtRequest.ontvanger
        logboekContext.dataSubjectType = "ontvanger"

        val bericht = opslagService.opslaanBericht(
            afzender = aanleverBerichtRequest.afzender,
            ontvanger = aanleverBerichtRequest.ontvanger,
            onderwerp = aanleverBerichtRequest.onderwerp,
            inhoud = aanleverBerichtRequest.inhoud,
        )

        val selfHref = uriInfo.baseUriBuilder
            .path("api")
            .path("v1")
            .path("berichten")
            .path(bericht.berichtId.toString())
            .build()

        return BerichtResponse().apply {
            berichtId = bericht.berichtId
            afzender = bericht.afzender
            ontvanger = bericht.ontvanger
            onderwerp = bericht.onderwerp
            tijdstip = bericht.tijdstip
            links = BerichtLinks().apply {
                self = Link().apply { href = selfHref }
            }
        }
    }
}
