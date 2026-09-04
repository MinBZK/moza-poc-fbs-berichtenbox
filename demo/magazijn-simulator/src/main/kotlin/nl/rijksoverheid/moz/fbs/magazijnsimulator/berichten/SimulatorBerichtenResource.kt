package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriBuilder
import jakarta.ws.rs.core.UriInfo
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.BerichtenApi
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtStatusPatch
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtenLijst
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.ONVERWACHTE_FOUT_DETAIL
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.problemResponse
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPad
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BerichtStatusWijziging
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie
import org.jboss.logging.Logger
import java.util.UUID
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Bericht as BerichtDto

/**
 * Alle operaties onder `/berichten` voor het magazijn dat het pad-filter heeft gekozen. Net als bij
 * het echte magazijn komt hier één tag uit de spec samen in één class: `GET` en `PATCH`/`DELETE`
 * delen het pad `/berichten/{berichtId}`, en twee tags onder dezelfde pad-root maken elkaars routes
 * onbereikbaar.
 *
 * Bewust géén eigen `@Path`: de paden komen uit [BerichtenApi]. Het magazijn-prefix is er door het
 * pad-filter afgehaald en komt in de HAL-links terug via [MagazijnPad.basisUri].
 *
 * De HTTP-laag blijft dun: welke statuscode bij welke situatie hoort, staat in [BerichtService],
 * omdat juist die volgorde het gedrag van een echt magazijn naspeelt.
 */
@ApplicationScoped
class SimulatorBerichtenResource(
    private val service: BerichtService,
    private val magazijnContext: MagazijnContext,
    @param:Context private val uriInfo: UriInfo,
    @param:Context private val request: ContainerRequestContext,
) : BerichtenApi {

    override fun getBerichten(
        xOntvanger: String,
        afzender: String?,
        page: Int?,
        pageSize: Int?,
    ): BerichtenLijst {
        val pagina = service.lijst(
            ontvanger = Identificatie.uitHeader(xOntvanger),
            afzender = afzender,
            page = page ?: DEFAULT_PAGE,
            pageSize = pageSize ?: DEFAULT_PAGE_SIZE,
        )

        return BerichtDtoMapper.naarBerichtenLijst(pagina, afzender, basis())
    }

    override fun getBerichtById(berichtId: UUID, xOntvanger: String): BerichtDto =
        BerichtDtoMapper.naarBericht(service.haalOp(berichtId, Identificatie.uitHeader(xOntvanger)), basis())

    override fun getBijlage(berichtId: UUID, bijlageId: UUID, xOntvanger: String): ByteArray {
        val bijlage = service.haalBijlageOp(berichtId, bijlageId, Identificatie.uitHeader(xOntvanger))

        // Een opgeslagen MIME-type dat niet te parsen is, kan sinds de vormcontrole op `Bijlage` niet
        // meer via een aanlevering binnenkomen; het zou met de hand aangepaste data zijn. Dan liever
        // geen bytes onder een verkeerd Content-Type serveren: 500, zodat het opvalt. De waarde blijft
        // uit het antwoord en staat alleen in de log — een MIME-type is geen persoonsgegeven.
        //
        // Het correlatie-id wordt hier gemaakt en in de antwoord-response meegegeven, zodat één
        // logregel zowel de bijlage als het id draagt dat de aanroeper te zien krijgt. Zou de mapper
        // zijn eigen id maken, dan staat de bijlageId in een regel zonder id en het id in een regel
        // zonder bijlageId, en is de melding van een aanroeper niet terug te zoeken.
        val mediaType = try {
            MediaType.valueOf(bijlage.mimeType)
        } catch (ex: IllegalArgumentException) {
            val foutId = UUID.randomUUID()

            log.errorf(
                ex,
                "Ongeldig MIME-type in opslag; geen inhoud geserveerd (bijlageId=%s, foutId=%s)",
                bijlageId,
                foutId,
            )

            throw WebApplicationException(
                problemResponse(
                    status = Response.Status.INTERNAL_SERVER_ERROR.statusCode,
                    title = "Internal Server Error",
                    detail = ONVERWACHTE_FOUT_DETAIL,
                    foutId = foutId,
                ),
            )
        }

        request.setProperty(BIJLAGE_MIME_TYPE_PROPERTY, mediaType.toString())
        request.setProperty(BIJLAGE_NAAM_PROPERTY, bijlage.naam)

        return bijlage.inhoud
    }

    override fun updateBerichtStatus(
        berichtId: UUID,
        xOntvanger: String,
        berichtStatusPatch: BerichtStatusPatch,
    ): BerichtDto {
        val bericht = service.wijzigStatus(
            berichtId = berichtId,
            ontvanger = Identificatie.uitHeader(xOntvanger),
            wijziging = BerichtStatusWijziging(berichtStatusPatch.gelezen, berichtStatusPatch.map),
        )

        return BerichtDtoMapper.naarBericht(bericht, basis())
    }

    override fun verwijderBericht(berichtId: UUID, xOntvanger: String) {
        service.verwijder(berichtId, Identificatie.uitHeader(xOntvanger))
    }

    private fun basis(): UriBuilder = MagazijnPad.basisUri(uriInfo, magazijnContext.magazijn.oin)

    private companion object {
        private val log: Logger = Logger.getLogger(SimulatorBerichtenResource::class.java)

        // Gelijk aan de defaults van `PageParam`/`PageSizeParam` in berichtenmagazijn-api.yaml.
        // De gegenereerde interface levert ze als `Int?`; een ontbrekende query-parameter mag niet
        // op een andere pagina uitkomen dan de spec belooft.
        const val DEFAULT_PAGE = 0
        const val DEFAULT_PAGE_SIZE = 20
    }
}
