package nl.rijksoverheid.moz.fbs.demopersonas

import io.quarkus.arc.properties.IfBuildProperty
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/** Eén persona: `label` in de keuzelijst, `ontvanger` als `X-Ontvanger`-header, `bron` als presentatie. */
data class PersonaDto(val id: String, val label: String, val ontvanger: String, val bron: String)

/**
 * De keuzelijst van demo-identiteiten voor een berichtenbox. Geen contract van het stelsel: dit
 * endpoint hoort bij de demo en staat daarom in geen enkele OpenAPI-spec. De veldnamen liggen wél
 * vast — een afnemende berichtenbox zoekt zijn testaccount op `bron` en `ontvanger`.
 *
 * Het aantal magazijnen per persona blijft eruit: dat is inrichting van de demo-omgeving en zegt
 * een berichtenbox niets.
 *
 * Alleen actief in deze dienst. Wie de module als afhankelijkheid opneemt krijgt het domein en de
 * lijst, maar niet dit pad: twee diensten die hetzelfde adres beantwoorden maken een verkeerd
 * gerichte proxy onzichtbaar, want beide antwoorden zijn dan gelijk.
 */
@IfBuildProperty(name = "personadienst.endpoint", stringValue = "true")
@Path("/api/demo/personas")
@Produces(MediaType.APPLICATION_JSON)
class PersonaResource(private val personaService: PersonaService) {

    @GET
    fun personas(): List<PersonaDto> = personaService.alle().map {
        PersonaDto(it.id, it.label, it.ontvanger, it.bron.wire)
    }
}
