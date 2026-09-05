package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService

@Path("/api/demo/ontdubbeling")
@Produces(MediaType.APPLICATION_JSON)
class OntdubbelingResource(
    private val service: OntdubbelingService,
    private val personaService: PersonaService,
) {

    /**
     * Op de persona-`id` en niet op zijn identificatienummer: een BSN hoort niet in een URL, ook
     * niet in een demo. Het nummer komt uit dezelfde ingerichte lijst als de keuzelijst.
     *
     * Bedieningsfouten als `WebApplicationException` en niet als `require()`, en `@DefaultValue("")`
     * in plaats van een niet-nullable parameter zonder default: om dezelfde redenen als bij
     * `POST /api/demo/bericht`, waar ze uitgeschreven staan. Een vaste persona als default zou hier
     * bovendien een identificatienummer terugzetten in deze broncode.
     */
    @POST
    fun demonstreer(@QueryParam("persona") @DefaultValue("") persona: String): OntdubbelingResultaat {
        if (persona.isBlank()) throw BadRequestException(KIES_EEN_PERSONA)

        // Weigeren én niet terugciteren. Dit adres nam tot voor kort het nummer zelf, dus een
        // aanroep met een BSN erin is het te verwachten verkeerde gebruik — en elke weigering gaat
        // onverkort naar de applicatielog, waar een identificatienummer niet hoort. Acht of negen
        // cijfers is de vorm van een KVK-nummer, BSN of RSIN, en nooit die van een persona-id.
        if (persona.matches(NUMMERVORM)) {
            throw BadRequestException("een persona wordt met zijn naam aangewezen, niet met een nummer; $KIES_EEN_PERSONA")
        }

        val gekozen = personaService.alle().firstOrNull { it.id == persona }
            ?: throw NotFoundException("onbekende persona '$persona'; $KIES_EEN_PERSONA")

        // Een 400 en geen 404: deze persona bestáát, hij kan dit scenario alleen niet spelen. De
        // aanmeld-webhook draagt de ontvanger als BSN, dus voor een andere identiteit valt er geen
        // event te bouwen. Het paneel biedt zo'n persona niet aan, maar dit adres staat open op de
        // origin van het paneel.
        if (gekozen.type != BSN) {
            throw BadRequestException("persona '$persona' heeft een ${gekozen.type} en geen $BSN; $KIES_EEN_PERSONA")
        }

        return service.demonstreer(gekozen.waarde)
    }

    private companion object {

        const val BSN = "BSN"

        val NUMMERVORM = Regex("[0-9]{8,9}")

        const val KIES_EEN_PERSONA = "kies een persona met een $BSN uit personas van /api/demo/omgeving"
    }
}
