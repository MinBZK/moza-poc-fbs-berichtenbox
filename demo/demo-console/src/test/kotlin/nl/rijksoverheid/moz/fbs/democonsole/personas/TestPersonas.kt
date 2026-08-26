package nl.rijksoverheid.moz.fbs.democonsole.personas

import nl.rijksoverheid.moz.fbs.democonsole.DemoConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.Properties

/** Vaste invulling van de configuratie-mapping, zodat tests zonder CDI een [PersonaService] bouwen. */
internal class VasteDemoConfig(
    private val personas: Map<String, DemoConfig.PersonaInstelling>,
    private val magazijnen: Map<String, DemoConfig.Magazijn> = TestPersonas.MAGAZIJNEN.associateWith { VastMagazijn },
) : DemoConfig {

    override fun magazijnen(): Map<String, DemoConfig.Magazijn> = magazijnen

    override fun personas(): Map<String, DemoConfig.PersonaInstelling> = personas
}

internal object VastMagazijn : DemoConfig.Magazijn {

    override fun url(): String = "http://localhost:8090"
}

/** `magazijnen = null` staat voor een ontbrekende property, `emptyList()` voor een lege waarde. */
internal class VastePersona(
    private val label: String,
    private val type: String,
    private val waarde: String,
    private val magazijnen: List<String>? = null,
    private val bron: String = "keten",
) : DemoConfig.PersonaInstelling {

    override fun label(): String = label

    override fun type(): String = type

    override fun waarde(): String = waarde

    override fun magazijnen(): Optional<List<String>> = Optional.ofNullable(magazijnen)

    override fun bron(): String = bron
}

internal object TestPersonas {

    const val RVO = "00000000000000100000"
    const val BELASTINGDIENST = "00000001823288444000"

    val MAGAZIJNEN = setOf(RVO, BELASTINGDIENST)

    // Relatief aan de module-root, de werkdirectory van Surefire.
    private const val BESTAND = "src/main/resources/application.properties"

    private val SLEUTEL = Regex("""demo\.personas\.([^.]+)\.([^.]+)""")

    /**
     * De persona's zoals ze in `application.properties` staan. Zonder dit raakt een pure-JVM-test
     * de echte lijst nooit aan en blijkt een typfout daarin pas bij het starten van de demo. Deze
     * parser is niet die van SmallRye; `PersonaConfiguratieTest` toetst dat de twee hetzelfde lezen.
     */
    fun uitApplicationProperties(): PersonaService {
        val eigenschappen = Properties()

        // Uit het bestand, niet van het classpath: in een @QuarkusTest levert de classloader een
        // ándere application.properties op dan de bron, waardoor deze parser leeg terugkwam.
        Files.newInputStream(Path.of(BESTAND)).use { eigenschappen.load(it) }

        val velden = eigenschappen.stringPropertyNames()
            .mapNotNull { sleutel -> SLEUTEL.matchEntire(sleutel)?.let { it.groupValues[1] to (it.groupValues[2] to eigenschappen.getProperty(sleutel)) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, paren) -> paren.toMap() }

        val magazijnen = eigenschappen.stringPropertyNames()
            .mapNotNull { MAGAZIJN_SLEUTEL.matchEntire(it)?.groupValues?.get(1) }
            .associateWith { VastMagazijn }

        return PersonaService(VasteDemoConfig(velden.mapValues { (id, veld) -> vastePersona(id, veld) }, magazijnen))
    }

    private val MAGAZIJN_SLEUTEL = Regex("""demo\.magazijnen\."(\d+)"\.url""")

    private fun vastePersona(id: String, veld: Map<String, String>): VastePersona {
        fun vereist(naam: String) = veld[naam] ?: error("demo-persona '$id' mist de property '$naam'")

        return VastePersona(
            label = vereist("label"),
            type = vereist("type"),
            waarde = vereist("waarde"),
            magazijnen = veld["magazijnen"]?.split(",")?.map { it.trim() },
            bron = vereist("bron"),
        )
    }
}
