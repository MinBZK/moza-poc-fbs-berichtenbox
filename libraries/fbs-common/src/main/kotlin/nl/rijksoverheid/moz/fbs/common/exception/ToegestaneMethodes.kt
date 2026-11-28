package nl.rijksoverheid.moz.fbs.common.exception

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.eclipse.microprofile.config.ConfigProvider
import org.jboss.logging.Logger
import java.net.URI
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

    // Alleen het pad: een absolute server-URL (https://host/api/v1) mag de basis niet breken.
    private val basis = URI.create(spec.path("servers").path(0).path("url").asText("")).path.orEmpty().trimEnd('/')

    // Zoals JAX-RS kiest: eerst het template met de meeste letterlijke tekens, dan het met de minste
    // parameters. Zo geldt `/berichten/zoeken` niet als `/berichten/{id}`.
    private val paden: List<Pair<Regex, Set<String>>> = spec.path("paths").fieldNames().asSequence()
        .map { template -> Triple(basis + template, patroon(basis + template), methodes(spec.path("paths").path(template))) }
        .sortedWith(compareByDescending<Triple<String, Regex, Set<String>>> { letterlijk(it.first) }.thenBy { parameters(it.first) })
        .map { (_, patroon, methodes) -> patroon to methodes }
        .toList()

    fun voor(pad: String): Set<String> {
        val genormaliseerd = "/" + pad.trim('/')

        return paden.firstOrNull { (patroon, _) -> patroon.matches(genormaliseerd) }?.second.orEmpty()
    }

    private fun letterlijk(template: String): Int = template.replace(PARAMETER, "").length

    private fun parameters(template: String): Int = PARAMETER.findAll(template).count()

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

        private val PARAMETER = Regex("\\{[^}]*}")

        private val log = Logger.getLogger(ToegestaneMethodes::class.java)

        @Volatile
        private var geladen: ToegestaneMethodes? = null

        @Volatile
        private var gelezen = false

        /**
         * Eén keer per dienst gelezen, bij de eerste 405. De configuratie wordt eerst bekeken: zonder
         * `fbs.openapi.spec` raakt niets de YAML-parser aan, zodat een dienst zonder jackson-yaml geen
         * `NoClassDefFoundError` krijgt.
         */
        fun vanDienst(): ToegestaneMethodes? {
            if (gelezen) return geladen

            synchronized(this) {
                if (!gelezen) {
                    geladen = laad(ConfigProvider.getConfig().getOptionalValue("fbs.openapi.spec", String::class.java).orElse(null))
                    gelezen = true
                }
            }

            return geladen
        }

        /** [pad] is de waarde van `fbs.openapi.spec`; `null` betekent dat de dienst geen spec noemt. */
        internal fun laad(pad: String?): ToegestaneMethodes? {
            if (pad == null) return null

            return try {
                ToegestaneMethodes(SpecLezer.lees(pad))
            } catch (ex: Exception) {
                log.warnf("OpenAPI-spec %s niet te lezen (%s); een 405 krijgt geen Allow-header", pad, ex.javaClass.name)
                null
            } catch (ex: LinkageError) {
                log.warnf("OpenAPI-spec %s niet te lezen (%s); een 405 krijgt geen Allow-header", pad, ex.javaClass.name)
                null
            }
        }
    }
}

/** Apart, zodat de YAML-klassen pas laden als er een spec is ingesteld. */
private object SpecLezer {

    fun lees(pad: String): JsonNode {
        val bron = Thread.currentThread().contextClassLoader.getResourceAsStream(pad)
            ?: throw IllegalStateException("niet op het classpath")

        return bron.use { ObjectMapper(YAMLFactory()).readTree(it) }
    }
}
