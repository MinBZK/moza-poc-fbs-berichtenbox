package nl.rijksoverheid.moz.fbs.democonsole.omgeving

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.democonsole.generator.DemoBerichtGenerator
import nl.rijksoverheid.moz.fbs.democonsole.generator.Doelpersona
import nl.rijksoverheid.moz.fbs.democonsole.storing.ToxiproxyRegister
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService

/**
 * Wat het paneel over zijn omgeving moet weten. Zonder dit endpoint zouden de storingsknoppen en
 * het adres van de berichtenbox per omgeving in de pagina staan — varianten van dezelfde pagina,
 * die gegarandeerd uit elkaar lopen.
 *
 * `personas` staat hier omdat het paneel een keuzelijst van identiteiten nodig heeft. Het adres
 * `/api/demo/personas` hoort bij de personadienst en wordt hier bewust niet ook beantwoord; de
 * lijst komt uit dezelfde `PersonaService` als die dienst gebruikt — de jar van `demo-personas` —
 * dus er is één bron.
 *
 * Per persona alleen de id en het label, en niet de `PersonaDto` van de personadienst: die draagt ook
 * het identificatienummer, en dat gebruikt het paneel nergens. Een berichtenbox heeft het wél nodig
 * om een sessie te starten en leest het daarom bij de personadienst, niet hier.
 *
 * `berichtPersonas` staat naast `personas` en niet erin: dit is de deelverzameling waarvoor de
 * console kán aanleveren — zonder magazijn is er geen bestemming, en een keuze die alleen een fout
 * kan opleveren hoort niet in het paneel. Die kennis zit in de inrichting van dit paneel en niet in
 * de personadienst, die het aantal magazijnen bewust weglaat. De lijst komt van de generator en niet
 * van de personadienst, omdat die van de generator precies de persona's bevat waarvoor hij een
 * bericht kán maken: met een ingericht magazijn én een organisatie waarvoor hij sjablonen heeft —
 * het init-blok van de generator eist beide.
 *
 * `simulator` zegt of deze omgeving gesimuleerde magazijnen kent, niet hoeveel: het aantal vraagt
 * het paneel aan de simulator zelf, zodat er geen tweede getal is dat daarvan kan afwijken. Het
 * paneel weet er wél vooraf mee of het die knoppen en die chip überhaupt moet tonen — zonder dat
 * onderscheid legt het een mislukte uitlezing uit als "niet ingericht".
 */
data class Omgeving(
    val personas: List<PaneelPersona>,
    val berichtPersonas: List<Doelpersona>,
    val berichtenboxUrl: String,
    val storingen: List<String>,
    val simulator: Boolean,
    val sessiecache: Boolean,
)

/** Een persona zoals het paneel hem kent: om aan te wijzen en te tonen, zonder zijn nummer. */
data class PaneelPersona(val id: String, val label: String)

@Path("/api/demo/omgeving")
@Produces(MediaType.APPLICATION_JSON)
class OmgevingResource(
    private val config: OmgevingConfig,
    private val register: ToxiproxyRegister,
    private val personaService: PersonaService,
    private val generator: DemoBerichtGenerator,
) {

    @GET
    fun omgeving(): Omgeving = Omgeving(
        personas = personaService.alle().map { PaneelPersona(id = it.id, label = it.label) },
        berichtPersonas = generator.doelgroep(),
        berichtenboxUrl = config.berichtenboxUrl().orElse(""),
        storingen = register.namen().sorted(),
        simulator = config.simulator(),
        sessiecache = config.sessiecache(),
    )
}
