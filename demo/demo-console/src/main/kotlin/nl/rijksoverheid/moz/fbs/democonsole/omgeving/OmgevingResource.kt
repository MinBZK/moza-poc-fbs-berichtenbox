package nl.rijksoverheid.moz.fbs.democonsole.omgeving

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.democonsole.storing.ToxiproxyRegister
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaDto
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService

/**
 * Wat de statische pagina's over hun omgeving moeten weten. Zonder dit endpoint zou de
 * Berichtenbox-pagina zijn API-adres moeten raden en zouden de storingsknoppen per omgeving
 * verschillen — twee varianten van dezelfde pagina, die gegarandeerd uit elkaar lopen.
 *
 * `personas` staat hier omdat de twee pagina's die deze module zelf serveert — het paneel en de
 * wegwerp-berichtenbox — een keuzelijst van identiteiten nodig hebben. Het adres `/api/demo/personas`
 * hoort bij de personadienst en wordt hier bewust niet ook beantwoord; de lijst komt uit dezelfde
 * module, dus er is één bron.
 *
 * `simulator` zegt of deze omgeving gesimuleerde magazijnen kent, niet hoeveel: het aantal vraagt
 * het paneel aan de simulator zelf, zodat er geen tweede getal is dat daarvan kan afwijken. Het
 * paneel weet er wél vooraf mee of het die knoppen en die chip überhaupt moet tonen — zonder dat
 * onderscheid legt het een mislukte uitlezing uit als "niet ingericht".
 */
data class Omgeving(
    val uitvraagBasis: String,
    val personas: List<PersonaDto>,
    val berichtenboxUrl: String,
    val storingen: List<String>,
    val simulator: Boolean,
    val sessiecache: Boolean,
)

@Path("/api/demo/omgeving")
@Produces(MediaType.APPLICATION_JSON)
class OmgevingResource(
    private val config: OmgevingConfig,
    private val register: ToxiproxyRegister,
    private val personaService: PersonaService,
) {

    @GET
    fun omgeving(): Omgeving = Omgeving(
        uitvraagBasis = config.uitvraagBasis().orElse(""),
        personas = personaService.alle().map { PersonaDto(it.id, it.label, it.ontvanger, it.bron.wire) },
        berichtenboxUrl = config.berichtenboxUrl().orElse(""),
        storingen = register.namen().sorted(),
        simulator = config.simulator(),
        sessiecache = config.sessiecache(),
    )
}
