package nl.rijksoverheid.moz.fbs.democonsole.sessie

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.democonsole.onbekendePersona
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService

@Path("/api/demo/sessie")
@Produces(MediaType.APPLICATION_JSON)
class SessieResource(
    private val sessieService: SessieService,
    private val beheer: SessieBeheer,
    private val personaService: PersonaService,
) {

    /** Wist de hele sessiecache zonder regel in het logboek; zie [SessieService]. */
    @POST
    @Path("/verlopen")
    fun verlopen(): VerlopenSessiecache = sessieService.laatSessiesVerlopen()

    @GET
    @Path("/lopend")
    fun lopend(): Sessieoverzicht = beheer.overzicht()

    /**
     * Laat één sessie verlopen, aangewezen met de [LopendeSessie.verwijzing] uit [lopend]. Een
     * verwijzing die niets (meer) aanwijst is geen fout: een tweede klik, of een sessie die intussen
     * al verliep. Alleen een waarde van een andere vorm wordt geweigerd.
     */
    @POST
    @Path("/beeindig")
    fun beeindig(@QueryParam("verwijzing") @DefaultValue("") verwijzing: String): Beeindigd {
        if (!Sessieverwijzer.isGeldig(verwijzing)) {
            throw BadRequestException("geen geldige sessieverwijzing; gebruik een verwijzing uit $LOPEND_PAD")
        }

        return Beeindigd(beheer.beeindig(verwijzing))
    }

    /** Laat elke lopende sessie van de persona verlopen, op de persona-`id` zoals bij de andere knoppen. */
    @POST
    @Path("/beeindig-persona")
    fun beeindigVanPersona(@QueryParam("persona") @DefaultValue("") persona: String): Beeindigd {
        if (persona.isBlank()) throw BadRequestException(KIES_EEN_PERSONA)

        val gekozen = personaService.alle().firstOrNull { it.id == persona }
            ?: throw onbekendePersona(persona, KIES_EEN_PERSONA)

        return Beeindigd(beheer.beeindigVan(gekozen))
    }

    private companion object {

        const val LOPEND_PAD = "/api/demo/sessie/lopend"

        const val KIES_EEN_PERSONA = "kies een persona uit personas van /api/demo/omgeving"
    }
}
