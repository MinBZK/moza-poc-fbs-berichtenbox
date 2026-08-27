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

    // Aanhalingstekens zijn optioneel: een OIN bevat geen punt, dus SmallRye accepteert beide vormen.
    private val MAGAZIJN_SLEUTEL = Regex("""demo\.magazijnen\."?(\d+)"?\.url""")

    private val SLEUTEL = Regex("""demo\.personas\.([^.]+)\.([^.]+)""")

    /**
     * De persona's zoals ze in `application.properties` staan. Zonder dit raakt een pure-JVM-test
     * de echte lijst nooit aan en blijkt een typfout daarin pas bij het starten van de demo. Deze
     * parser is niet die van SmallRye; `PersonaConfiguratieTest` toetst dat de twee hetzelfde lezen.
     */
    fun uitApplicationProperties(): PersonaService {
        val eigenschappen = laadEigenschappen()

        val magazijnen = eigenschappen.stringPropertyNames()
            .mapNotNull { MAGAZIJN_SLEUTEL.matchEntire(it)?.groupValues?.get(1) }
            .associateWith { VastMagazijn }

        check(magazijnen.isNotEmpty()) { "geen demo.magazijnen-sleutel gevonden in $BESTAND; klopt MAGAZIJN_SLEUTEL nog?" }

        val velden = personaVelden(eigenschappen)

        return PersonaService(VasteDemoConfig(velden.mapValues { (id, veld) -> vastePersona(id, veld) }, magazijnen))
    }

    private fun laadEigenschappen(): Properties {
        val eigenschappen = Properties()

        // Uit het bestand, niet van het classpath: PersonaConfiguratieTest is een @QuarkusTest, en
        // daar levert de classloader een andere application.properties dan de bron in de repo —
        // deze parser kwam dan leeg terug.
        val pad = Path.of(BESTAND)

        check(Files.isReadable(pad)) { "$BESTAND niet leesbaar — draait de test vanaf de module-root?" }

        Files.newInputStream(pad).use { eigenschappen.load(it) }

        // Deze parser leest geen profiel-sleutels: stilzwijgend negeren zou een divergentie met
        // SmallRye opleveren die pas bij het starten van de demo blijkt. Over de hele demo-prefix,
        // want een profiel-scoped magazijn zou de persona die ernaar wijst ten onrechte doen falen.
        eigenschappen.stringPropertyNames().forEach {
            check(!it.startsWith("%") || !it.contains(".demo.")) { "profiel-sleutel '$it' wordt hier niet gelezen" }
        }

        return eigenschappen
    }

    /** Per persona-id de gelezen velden: `demo.personas.<id>.<veld>` = waarde. */
    private fun personaVelden(eigenschappen: Properties): Map<String, Map<String, String>> {
        val velden = eigenschappen.stringPropertyNames()
            .mapNotNull { SLEUTEL.matchEntire(it) }
            .groupBy({ it.groupValues[1] }, { it.groupValues[2] to eigenschappen.getProperty(it.value) })
            .mapValues { (_, paren) -> paren.toMap() }

        check(velden.isNotEmpty()) { "geen demo.personas-sleutel gevonden in $BESTAND; klopt SLEUTEL nog?" }

        // Van de magazijn-sleutels wordt alleen de OIN in de key gelezen, nooit de waarde; daar is
        // een expressie dus onschadelijk (de URL's gebruiken er al een).
        velden.values.flatMap { it.values }.forEach {
            check(!it.contains("\${")) { "expressie in demo.personas.*: '$it' wordt hier niet geëxpandeerd" }
        }

        return velden
    }

    private fun vastePersona(id: String, veld: Map<String, String>): VastePersona {
        fun vereist(naam: String) = veld[naam] ?: error("demo-persona '$id' mist de property '$naam'")

        return VastePersona(
            label = vereist("label"),
            type = vereist("type"),
            waarde = vereist("waarde"),
            // Zoals io.smallrye.config.common.utils.StringUtil.split: geen trim, lege segmenten
            // vervallen. Trimmen zou een spatie na de komma verbergen die de echte boot laat falen.
            magazijnen = veld["magazijnen"]?.split(",")?.filter { it.isNotEmpty() },
            bron = vereist("bron"),
        )
    }
}
