package nl.rijksoverheid.moz.fbs.democonsole.storing

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * Bewaakt dat de twee bronnen van dezelfde feiten niet uiteenlopen: `toxiproxy/proxies.json`, waar
 * compose de proxies lokaal uit zet, en de defaults in `application.properties`, waarmee de console
 * ze op ZAD zelf aanmaakt.
 *
 * Zonder deze test wordt een verkeerde listen of upstream pas op ZAD zichtbaar, en dan als een
 * preview die stilzwijgend het verkeer van `test` afhandelt — precies de faalwijze waarvoor de
 * console de proxies zelf is gaan aanmaken.
 *
 * Bewust géén `@QuarkusTest`: dit leest beide bestanden rechtstreeks van disk en draait dus zonder
 * Docker.
 */
class ToxiproxyProxiesConsistentieTest {

    private val properties = Properties().apply {
        File("src/main/resources/application.properties").inputStream().use { load(it) }
    }

    private val uitJson = ObjectMapper().readTree(File(PROXIES_JSON)).associate { proxy ->
        proxy["name"].asText() to (proxy["listen"].asText() to proxy["upstream"].asText())
    }

    /** De waarde achter de dubbele punt in `${ENV_VAR:default}`. */
    private fun default(sleutel: String): String {
        val waarde = properties.getProperty(sleutel)
            ?: throw AssertionError("$sleutel ontbreekt in application.properties")

        return ENV_MET_DEFAULT.matchEntire(waarde)?.groupValues?.get(1)
            ?: throw AssertionError("$sleutel heeft geen \${ENV:default}-vorm maar '$waarde'")
    }

    private fun namenUitProperties(): Set<String> =
        properties.stringPropertyNames().mapNotNull { NAAM_UIT_LISTEN.matchEntire(it)?.groupValues?.get(1) }.toSet()

    @Test
    fun `beide bronnen kennen dezelfde proxies`() {
        // Een proxy die alleen in proxies.json staat, wordt op ZAD nooit aangemaakt en zijn stroom
        // loopt daar nergens doorheen; een proxy die alleen in application.properties staat, krijgt
        // lokaal geen listener en faalt bij de eerste aanroep.
        assertEquals(uitJson.keys, namenUitProperties())
    }

    @Test
    fun `elke proxy luistert en stuurt door naar hetzelfde adres in beide bronnen`() {
        uitJson.forEach { (naam, adressen) ->
            val (listen, upstream) = adressen

            assertEquals(listen, default("demo.toxiproxy.\"$naam\".listen"), "listen van $naam")
            assertEquals(upstream, default("demo.toxiproxy.\"$naam\".upstream"), "upstream van $naam")
        }
    }

    @Test
    fun `elke proxy die een url kan dragen, draagt ook een listen en een upstream`() {
        // De url zet de proxy aan of uit per omgeving; listen en upstream horen er dan altijd bij.
        // Ontbreekt er één, dan slaat ProxyBootstrap die proxy over en is de stroom dood zonder dat
        // een knop dat laat zien.
        val metUrl = properties.stringPropertyNames()
            .mapNotNull { NAAM_UIT_URL.matchEntire(it)?.groupValues?.get(1) }
            .toSet()

        assertEquals(metUrl, namenUitProperties())
    }

    private companion object {

        const val PROXIES_JSON = "../../toxiproxy/proxies.json"

        val ENV_MET_DEFAULT = """\$\{[A-Z0-9_]+:(.*)}""".toRegex()

        val NAAM_UIT_LISTEN = """demo\.toxiproxy\."([^"]+)"\.listen""".toRegex()

        val NAAM_UIT_URL = """demo\.toxiproxy\."([^"]+)"\.url""".toRegex()
    }
}
