package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.beheerconsole.api.StelselApi
import nl.rijksoverheid.moz.fbs.beheerconsole.api.model.Documentgegevens
import nl.rijksoverheid.moz.fbs.beheerconsole.api.model.Link
import nl.rijksoverheid.moz.fbs.beheerconsole.api.model.Links
import nl.rijksoverheid.moz.fbs.beheerconsole.api.model.Ondertekening
import nl.rijksoverheid.moz.fbs.beheerconsole.api.model.Organisatie
import nl.rijksoverheid.moz.fbs.beheerconsole.api.model.Stelseloverzicht
import nl.rijksoverheid.moz.fbs.beheerconsole.api.model.Verificatie
import nl.rijksoverheid.moz.fbs.common.exception.FbsFoutException
import nl.rijksoverheid.moz.fbs.common.exception.Foutcode
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.net.URI
import java.time.Clock

class StelselResource(
    @param:RestClient private val stelselregister: StelselregisterClient,
    private val lezer: Stelseldocumentlezer,
    private val klok: Clock,
) : StelselApi {

    private val log = Logger.getLogger(StelselResource::class.java)

    override fun getStelseloverzicht(): Stelseloverzicht {
        val jws = try {
            stelselregister.stelseldocument()
        } catch (e: WebApplicationException) {
            throw bronFout("antwoordde met ${e.response.status}", e)
        } catch (e: ProcessingException) {
            throw bronFout("was niet bereikbaar", e)
        }

        val gelezen = lezer.lees(jws, klok.instant())

        if (gelezen.uitkomst !is Stelseldocumentlezer.Uitkomst.Geldig) {
            log.warnf("Stelseldocument niet geldig: %s", gelezen.uitkomst)
        }

        val organisaties = gelezen.inhoud?.organisaties.orEmpty().map(::organisatie)

        return Stelseloverzicht(verificatie(gelezen.uitkomst), organisaties, LINKS)
            .document(gelezen.inhoud?.let(::document))
            .ondertekening(gelezen.ondertekening?.let(::ondertekening))
    }

    private fun bronFout(wat: String, oorzaak: Exception): FbsFoutException {
        log.warnf("Het stelselregister %s: %s", wat, oorzaak.javaClass.simpleName)

        return FbsFoutException(Foutcode.KETEN_FOUT, Response.Status.BAD_GATEWAY, "Het stelselregister $wat", oorzaak)
    }

    private fun verificatie(uitkomst: Stelseldocumentlezer.Uitkomst): Verificatie = when (uitkomst) {
        Stelseldocumentlezer.Uitkomst.Geldig -> Verificatie(Verificatie.StatusEnum.GELDIG)
        is Stelseldocumentlezer.Uitkomst.Ongeldig -> Verificatie(Verificatie.StatusEnum.ONGELDIG).reden(uitkomst.reden)
        is Stelseldocumentlezer.Uitkomst.NietGetoetst -> Verificatie(Verificatie.StatusEnum.NIET_GETOETST).reden(uitkomst.reden)
    }

    private fun document(inhoud: Stelseldocumentlezer.Inhoud) = Documentgegevens(
        inhoud.uitgever,
        inhoud.omgeving,
        inhoud.versie,
        inhoud.uitgegevenOp,
        inhoud.geldigTot,
    )

    private fun ondertekening(o: Stelseldocumentlezer.Ondertekening) =
        Ondertekening(o.kid, o.onderwerp, o.certificaatGeldigTot)

    private fun organisatie(o: Stelseldocumentlezer.Organisatie) = Organisatie(o.oin, o.naam, URI.create(o.magazijnUrl))

    private companion object {
        /** Relatief, zonder schema en host: die verschillen per omgeving achter de gateway. */
        val LINKS = Links(Link("/api/v1/stelsel"))
    }
}
