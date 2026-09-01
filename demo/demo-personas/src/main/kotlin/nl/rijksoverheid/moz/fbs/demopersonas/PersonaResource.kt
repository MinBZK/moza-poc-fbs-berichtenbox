package nl.rijksoverheid.moz.fbs.demopersonas

import io.quarkus.arc.properties.IfBuildProperty
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/** Eén persona: `label` in de keuzelijst, `ontvanger` als `X-Ontvanger`-header, `bron` als presentatie. */
data class PersonaDto(val id: String, val label: String, val ontvanger: String, val bron: PersonaBron)

/**
 * De enige plek waar een [DemoPersona] zijn lijnvorm krijgt. Twee handgeschreven mappings met vier
 * positionele strings zouden `label` en `ontvanger` laten verwisselen zonder compilatiefout, en de
 * omzetting van [PersonaBron] naar zijn lijnvorm laten vergeten — waarna een afnemer die op
 * `"keten"` zoekt `"KETEN"` krijgt en stil niets meer vindt.
 */
fun DemoPersona.naarDto(): PersonaDto = PersonaDto(id, label, ontvanger, bron)

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
 *
 * Wie deze module opneemt zet `personadienst.endpoint=false` in zijn eigen `application.properties`
 * (ordinal 250, wint van de 100 uit deze jar). Dat staat ook in `demo/README.md`, want vergeten is
 * hier een stille fout: twee gelijke antwoorden op één adres zie je niet.
 *
 * De vlag hangt niet aan `quarkus.application.name`, hoe verleidelijk dat ook is: die waarde komt
 * uit deze jar en geldt in het gebouwde image wél maar in een test níét — daar valt Quarkus terug
 * op het artifactId. Een voorwaarde die in tests iets anders betekent dan in productie is erger dan
 * een vlag die je kunt vergeten.
 */
@IfBuildProperty(name = "personadienst.endpoint", stringValue = "true")
@Path("/api/demo/personas")
@Produces(MediaType.APPLICATION_JSON)
class PersonaResource(private val personaService: PersonaService) {

    @GET
    fun personas(): List<PersonaDto> = personaService.alle().map { it.naarDto() }
}
