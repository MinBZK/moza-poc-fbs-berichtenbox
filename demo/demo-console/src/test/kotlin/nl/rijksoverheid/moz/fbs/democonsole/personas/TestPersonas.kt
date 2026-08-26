package nl.rijksoverheid.moz.fbs.democonsole.personas

import nl.rijksoverheid.moz.fbs.democonsole.aanlever.DemoConfig
import java.util.Optional
import java.util.Properties

/** Vaste invulling van de configuratie-mapping, zodat tests zonder CDI een [PersonaService] bouwen. */
internal class VasteDemoConfig(private val personas: Map<String, DemoConfig.PersonaInstelling>) : DemoConfig {

    override fun magazijnen(): Map<String, DemoConfig.Magazijn> = emptyMap()

    override fun personas(): Map<String, DemoConfig.PersonaInstelling> = personas
}

internal class VastePersona(
    private val label: String,
    private val type: String,
    private val waarde: String,
    private val magazijnen: List<String> = emptyList(),
    private val bron: String = "keten",
) : DemoConfig.PersonaInstelling {

    override fun label(): String = label

    override fun type(): String = type

    override fun waarde(): String = waarde

    override fun magazijnen(): Optional<List<String>> = Optional.of(magazijnen)

    override fun bron(): String = bron
}

internal object TestPersonas {

    private val SLEUTEL = Regex("""demo\.personas\.([^.]+)\.([^.]+)""")

    /**
     * De persona's zoals ze in `application.properties` staan. Zonder dit raakt een pure-JVM-test
     * de echte lijst nooit aan en blijkt een typfout daarin pas bij het starten van de demo.
     */
    fun uitApplicationProperties(): PersonaService {
        val eigenschappen = Properties()

        javaClass.classLoader.getResourceAsStream("application.properties").use { eigenschappen.load(it) }

        val velden = eigenschappen.stringPropertyNames()
            .mapNotNull { sleutel -> SLEUTEL.matchEntire(sleutel)?.let { it.groupValues[1] to (it.groupValues[2] to eigenschappen.getProperty(sleutel)) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, paren) -> paren.toMap() }

        return PersonaService(VasteDemoConfig(velden.mapValues { (_, veld) -> vastePersona(veld) }))
    }

    private fun vastePersona(veld: Map<String, String>) =
        VastePersona(
            label = veld.getValue("label"),
            type = veld.getValue("type"),
            waarde = veld.getValue("waarde"),
            magazijnen = veld["magazijnen"]?.split(",")?.map { it.trim() }.orEmpty(),
            bron = veld["bron"] ?: "keten",
        )
}
