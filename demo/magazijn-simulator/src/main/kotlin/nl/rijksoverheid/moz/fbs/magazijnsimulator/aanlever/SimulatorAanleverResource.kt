package nl.rijksoverheid.moz.fbs.magazijnsimulator.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.UriInfo
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.AanleverApi
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtAanleverenRequest
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtResponse
import nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten.BerichtDtoMapper
import nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten.BerichtService
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPad
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.IdentificatieType
import java.util.UUID

/**
 * Aanlevering bij het magazijn dat het pad-filter heeft gekozen.
 *
 * De simulator kent, anders dan het echte magazijn, géén abonnementscontrole bij de Profiel-service
 * en geen beperking van bijlagen tot PDF. Allebei staan ze niet in de spec — het zijn beleidskeuzes
 * van dát magazijn — en allebei zouden ze hier iets kosten zonder iets te tonen: de eerste een
 * externe afhankelijkheid in honderdvoud, de tweede een demo waarin alleen PDF's bestaan.
 */
@ApplicationScoped
class SimulatorAanleverResource(
    private val service: BerichtService,
    private val magazijnContext: MagazijnContext,
    @param:Context private val uriInfo: UriInfo,
) : AanleverApi {

    override fun leverBerichtAan(berichtAanleverenRequest: BerichtAanleverenRequest): BerichtResponse {
        val bericht = service.leverAan(
            afzender = Identificatie(IdentificatieType.OIN, berichtAanleverenRequest.afzender),
            ontvanger = Identificatie(
                IdentificatieType.valueOf(berichtAanleverenRequest.ontvanger.type.value()),
                berichtAanleverenRequest.ontvanger.waarde,
            ),
            onderwerp = berichtAanleverenRequest.onderwerp,
            inhoud = berichtAanleverenRequest.inhoud,
            publicatietijdstip = berichtAanleverenRequest.publicatietijdstip,
            bijlagen = berichtAanleverenRequest.bijlagen.orEmpty().map { aangeleverd ->
                Bijlage(
                    bijlageId = UUID.randomUUID(),
                    naam = aangeleverd.naam,
                    mimeType = aangeleverd.mimeType,
                    inhoud = aangeleverd.inhoud,
                )
            },
        )

        return BerichtDtoMapper.naarBerichtResponse(
            bericht,
            MagazijnPad.basisUri(uriInfo, magazijnContext.magazijn.oin),
        )
    }
}
