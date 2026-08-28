package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.BerichtenApi
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Bericht
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtStatusPatch
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtenLijst
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.problemResponse
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPad
import java.util.UUID

/**
 * Alle operaties onder `/berichten` voor het magazijn dat het pad-filter heeft gekozen. Net als bij
 * het echte magazijn komt hier één tag uit de spec samen in één class: `GET` en `PATCH`/`DELETE`
 * delen het pad `/berichten/{berichtId}`, en twee tags onder dezelfde pad-root maken elkaars routes
 * onbereikbaar.
 *
 * Bewust géén eigen `@Path`: de paden komen uit [BerichtenApi]. Het magazijn-prefix is er door het
 * pad-filter afgehaald en komt in de HAL-links terug via
 * [nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPad.basisUri].
 *
 * **Nog geen opslag.** De simulator draagt in deze stap alleen de buitenkant: de lijst is leeg en
 * elk bericht bestaat niet. Dat is geen tijdelijke hack maar de toestand van een leeg magazijn, en
 * dus spec-conform — opslag komt er in de volgende stap onder.
 */
@ApplicationScoped
class SimulatorBerichtenResource(
    private val magazijnContext: MagazijnContext,
    @param:Context private val uriInfo: UriInfo,
) : BerichtenApi {

    override fun getBerichten(
        xOntvanger: String,
        afzender: String?,
        page: Int?,
        pageSize: Int?,
    ): BerichtenLijst = BerichtenLijstMapper.leeg(
        page = page ?: DEFAULT_PAGE,
        pageSize = pageSize ?: DEFAULT_PAGE_SIZE,
        afzender = afzender,
        baseUri = MagazijnPad.basisUri(uriInfo, magazijnContext.magazijn.oin),
    )

    override fun getBerichtById(berichtId: UUID, xOntvanger: String): Bericht =
        throw berichtBestaatNiet(berichtId)

    override fun getBijlage(berichtId: UUID, bijlageId: UUID, xOntvanger: String): ByteArray =
        throw berichtBestaatNiet(berichtId)

    override fun updateBerichtStatus(
        berichtId: UUID,
        xOntvanger: String,
        berichtStatusPatch: BerichtStatusPatch,
    ): Bericht = throw berichtBestaatNiet(berichtId)

    override fun verwijderBericht(berichtId: UUID, xOntvanger: String): Unit =
        throw berichtBestaatNiet(berichtId)

    /**
     * Noemt het magazijn in het antwoord. Bij honderd magazijnen is "bericht niet gevonden" zonder
     * die vermelding niet te onderscheiden van "op het verkeerde magazijn uitgekomen", en dat
     * verschil is precies waar deze stap over gaat.
     */
    private fun berichtBestaatNiet(berichtId: UUID) = WebApplicationException(
        problemResponse(
            status = Response.Status.NOT_FOUND.statusCode,
            title = "Not Found",
            detail = "Bericht $berichtId bestaat niet in magazijn ${magazijnContext.magazijn.oin}",
        ),
    )

    private companion object {
        // Gelijk aan de defaults van `PageParam`/`PageSizeParam` in berichtenmagazijn-api.yaml.
        // De gegenereerde interface levert ze als `Int?`; een ontbrekende query-parameter mag niet
        // op een andere pagina uitkomen dan de spec belooft.
        const val DEFAULT_PAGE = 0
        const val DEFAULT_PAGE_SIZE = 20
    }
}
