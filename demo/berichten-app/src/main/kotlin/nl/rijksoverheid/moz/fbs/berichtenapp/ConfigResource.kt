package nl.rijksoverheid.moz.fbs.berichtenapp

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * De adressen die de pagina nodig heeft. Een endpoint en geen inline script: de CSP staat alleen
 * script van deze origin toe, en de waarden verschillen per omgeving.
 */
@Path("config.json")
class ConfigResource(private val config: BerichtenAppConfig) {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun config(): Map<String, Any> =
        mapOf("appManager" to mapOf("url" to config.appManagerUrl(), "oin" to config.appManagerOin()))
}
