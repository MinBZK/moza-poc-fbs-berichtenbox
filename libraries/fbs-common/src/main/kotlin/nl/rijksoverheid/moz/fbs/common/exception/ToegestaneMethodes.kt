package nl.rijksoverheid.moz.fbs.common.exception

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.eclipse.microprofile.config.ConfigProvider
import org.jboss.logging.Logger
import java.util.Locale

/**
 * De methodes die een pad volgens de OpenAPI-spec van de dienst kent, voor de `Allow` van een 405.
 * Quarkus REST zet die header niet: het framework geeft een kale 405 door. De spec is de bron van
 * waarheid en de resources implementeren de gegenereerde interfaces, dus die twee lopen gelijk.
 *
 * Een dienst noemt zijn spec in `fbs.openapi.spec` (een classpath-pad). Zonder die instelling, of als
 * de spec niet te lezen is, blijft de verzameling leeg: dan liever geen `Allow` dan een onjuiste.
 */
internal class ToegestaneMethodes(spec: JsonNode) {

    private val basis = spec.path("servers").path(0).path("url").asText("").trimEnd('/')

    // Letterlijke paden vóór paden met parameters, zodat `/berichten/zoeken` niet als
    // `/berichten/{id}` geldt.
    private val paden: List<Pair<Regex, Set<String>>> = spec.path("paths").fieldNames().asSequence()
        .map { template -> patroon(basis + template) to methodes(spec.path("paths").path(template)) }
        .toList()
        .sortedBy { (patroon, _) -> patroon.pattern.count { it == '[' } }

    fun voor(pad: String): Set<String> {
        val genormaliseerd = "/" + pad.trim('/')

        return paden.firstOrNull { (patroon, _) -> patroon.matches(genormaliseerd) }?.second.orEmpty()
    }

    private fun patroon(template: String): Regex =
        Regex(template.split('/').joinToString("/") { deel -> if (deel.startsWith("{")) "[^/]+" else Regex.escape(deel) })

    private fun methodes(operaties: JsonNode): Set<String> {
        val gedeclareerd: Set<String> = operaties.fieldNames().asSequence()
            .map { it.uppercase(Locale.ROOT) }
            .filter { it in HTTP_METHODES }
            .toSet()

        if (gedeclareerd.isEmpty()) return emptySet()

        // Quarkus beantwoordt HEAD bij elke GET en OPTIONS bij elk pad zelf.
        val impliciet = if ("GET" in gedeclareerd) setOf("HEAD", "OPTIONS") else setOf("OPTIONS")

        return gedeclareerd + impliciet
    }

    companion object {
        private val HTTP_METHODES = setOf("GET", "PUT", "POST", "DELETE", "PATCH", "HEAD", "OPTIONS")

        private val log = Logger.getLogger(ToegestaneMethodes::class.java)

        /** Eén keer per dienst gelezen; een 405 is zeldzaam, het inlezen gebeurt pas bij de eerste. */
        val vanDienst: ToegestaneMethodes? by lazy { laad() }

        private fun laad(): ToegestaneMethodes? {
            val pad = ConfigProvider.getConfig().getOptionalValue("fbs.openapi.spec", String::class.java).orElse(null)
                ?: return null

            return try {
                val bron = Thread.currentThread().contextClassLoader.getResourceAsStream(pad)
                    ?: throw IllegalStateException("niet op het classpath")

                bron.use { ToegestaneMethodes(ObjectMapper(YAMLFactory()).readTree(it)) }
            } catch (ex: Exception) {
                log.warnf("OpenAPI-spec %s niet te lezen (%s); een 405 krijgt geen Allow-header", pad, ex.javaClass.name)
                null
            }
        }
    }
}
