package nl.rijksoverheid.moz.fbs.democonsole.storing

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/** Toxic-verzoek: `{"type":"latency","attributes":{"latency":6000}}`. */
data class ToxicVerzoek(val type: String, val attributes: Map<String, Int>)

/** Proxy aan/uit: `{"enabled":false}`. */
data class ProxyPatch(val enabled: Boolean)

/** Nieuwe proxy: `{"name":"profiel","listen":"0.0.0.0:18089","upstream":"profiel-service:8080"}`. */
data class ProxyVerzoek(val name: String, val listen: String, val upstream: String, val enabled: Boolean = true)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToxicStatus(val name: String)

/**
 * Zoals Toxiproxy een proxy teruggeeft. `listen` is het adres waarop hij daadwerkelijk gebónden is,
 * niet wat er gepost werd: `0.0.0.0:18089` komt terug als `[::]:18089`. Vergelijk daarom de poort en
 * niet de hele string, anders lijkt elke proxy voortdurend afgeweken.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ProxyStatus(
    val enabled: Boolean,
    val toxics: List<ToxicStatus> = emptyList(),
    val listen: String = "",
    val upstream: String = "",
)

/**
 * Client voor de Toxiproxy-admin-API. Alleen de calls die de demo nodig heeft: proxies
 * lezen (voor reset), proxy aanmaken, proxy aan/uit, latency-toxic toevoegen/verwijderen.
 */
@Path("/proxies")
@RegisterRestClient(configKey = "toxiproxy")
interface ToxiproxyClient {

    @GET
    fun proxies(): Map<String, ProxyStatus>

    /** Maakt een proxy aan; bestaat hij al, dan antwoordt Toxiproxy 409 en verandert er niets. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    fun maakProxy(verzoek: ProxyVerzoek): Response

    @POST
    @Path("/{proxy}")
    @Consumes(MediaType.APPLICATION_JSON)
    fun zetProxy(@PathParam("proxy") proxy: String, patch: ProxyPatch): Response

    @POST
    @Path("/{proxy}/toxics")
    @Consumes(MediaType.APPLICATION_JSON)
    fun voegToxicToe(@PathParam("proxy") proxy: String, toxic: ToxicVerzoek): Response

    @DELETE
    @Path("/{proxy}/toxics/{toxic}")
    fun verwijderToxic(@PathParam("proxy") proxy: String, @PathParam("toxic") toxic: String): Response

    /** Verwijdert een proxy; nodig om er één met gewijzigde listen of upstream opnieuw te bouwen. */
    @DELETE
    @Path("/{proxy}")
    fun verwijderProxy(@PathParam("proxy") proxy: String): Response
}
