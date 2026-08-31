package nl.rijksoverheid.moz.fbs.democonsole.omgeving

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.democonsole.storing.ToxiproxyRegister
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Wat de statische pagina's over hun omgeving moeten weten. Zonder dit endpoint zou de
 * Berichtenbox-pagina zijn API-adres moeten raden en zouden de storingsknoppen per omgeving
 * verschillen — twee varianten van dezelfde pagina, die gegarandeerd uit elkaar lopen.
 *
 * `stubMagazijnen` is het ingerichte aantal, niet het actieve: daarmee weet het paneel vooraf of
 * deze omgeving die knoppen en die chip überhaupt kent. Zonder dat onderscheid moet het paneel een
 * mislukte uitlezing uitleggen als "niet ingericht", en dat is precies de verwarring die het moet
 * wegnemen.
 */
data class Omgeving(
    val uitvraagBasis: String,
    val storingen: List<String>,
    val stubMagazijnen: Int,
    val sessiecache: Boolean,
)

@Path("/api/demo/omgeving")
@Produces(MediaType.APPLICATION_JSON)
class OmgevingResource(
    private val config: OmgevingConfig,
    private val register: ToxiproxyRegister,
    @param:ConfigProperty(name = "veel-magazijnen.aantal") private val stubMagazijnen: Int,
) {

    @GET
    fun omgeving(): Omgeving = Omgeving(
        uitvraagBasis = config.uitvraagBasis().orElse(""),
        storingen = register.namen().sorted(),
        stubMagazijnen = stubMagazijnen,
        sessiecache = config.sessiecache(),
    )
}
