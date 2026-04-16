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
        val bericht = opslagService.opslaanBericht(
            afzender = aanleverBerichtRequest.afzender,
            ontvanger = aanleverBerichtRequest.ontvanger,
            onderwerp = aanleverBerichtRequest.onderwerp,
            inhoud = aanleverBerichtRequest.inhoud,
        )

        // Zet dataSubjectId pas na succesvolle domein-validatie, zodat we geen
        // ongevalideerde input in de AVG-logboekcontext zetten. Tot dat punt zorgt
        // LogboekContextDefaultFilter voor een safe default. Gebruik de door
        // [Bericht] gevalideerde waarde, niet het raw request-veld.
        logboekContext.dataSubjectId = bericht.ontvanger.waarde
        logboekContext.dataSubjectType = "ontvanger"

        val selfHref = uriInfo.baseUriBuilder
            .path("api")
            .path("v1")
            .path("berichten")
            .path(bericht.berichtId.toString())
            .build()

        return BerichtResponse().apply {
            berichtId = bericht.berichtId
            afzender = bericht.afzender.waarde
            ontvanger = bericht.ontvanger.waarde
            onderwerp = bericht.onderwerp
            tijdstip = bericht.tijdstip
            links = BerichtLinks().apply {
                self = Link().apply { href = selfHref }
            }
        }
    }
}
