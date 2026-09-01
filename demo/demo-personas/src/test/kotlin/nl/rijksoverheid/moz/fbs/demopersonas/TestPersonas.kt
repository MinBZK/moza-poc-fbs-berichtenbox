package nl.rijksoverheid.moz.fbs.demopersonas

import java.util.Optional
import java.util.Properties

/** Vaste invulling van de configuratie-mapping, zodat tests zonder CDI een [PersonaService] bouwen. */
internal class VastePersonaConfig(
    private val personas: Map<String, PersonaConfig.PersonaInstelling>,
) : PersonaConfig {

    override fun personas(): Map<String, PersonaConfig.PersonaInstelling> = personas
}

/** `magazijnen = null` staat voor een ontbrekende property, `emptyList()` voor een lege waarde. */
internal class VastePersona(
    private val label: String,
    private val type: String,
    private val waarde: String,
    private val magazijnen: List<String>? = null,
    private val bron: String = "keten",
) : PersonaConfig.PersonaInstelling {

    override fun label(): String = label

    override fun type(): String = type

    override fun waarde(): String = waarde

    override fun magazijnen(): Optional<List<String>> = Optional.ofNullable(magazijnen)

    override fun bron(): String = bron
}

/**
 * Niet `internal`: deze hulp gaat als test-jar mee naar de demo-console, die zijn eigen dataset en
 * ondernemerslijst tegen dezelfde ingerichte persona's toetst.
 */
object TestPersonas {

    const val RVO = "00000000000000100000"
    const val BELASTINGDIENST = "00000001823288444000"

    val MAGAZIJNEN = setOf(RVO, BELASTINGDIENST)

    // Van het classpath en niet van schijf: deze hulp draait ook in de demo-console, waar dit
    // bestand uit de jar van deze module komt en geen pad op schijf heeft. Er is er precies één —
    // application.properties blijft per applicatie en draagt geen persona's.
    private const val BESTAND = "META-INF/microprofile-config.properties"

    private val SLEUTEL = Regex("""demo\.personas\.([^.]+)\.([^.]+)""")

    /**
     * De persona's zoals ze in `application.properties` staan. Zonder dit raakt een pure-JVM-test
     * de echte lijst nooit aan en blijkt een typfout daarin pas bij het starten van de demo. Deze
     * parser is niet die van SmallRye; `PersonaConfiguratieTest` toetst dat de twee hetzelfde lezen.
     */
    fun uitApplicationProperties(): PersonaService {
        val velden = personaVelden(laadEigenschappen())

        return PersonaService(VastePersonaConfig(velden.mapValues { (id, veld) -> vastePersona(id, veld) }))
    }

    private fun laadEigenschappen(): Properties {
        val eigenschappen = Properties()

        val stroom = checkNotNull(TestPersonas::class.java.classLoader.getResourceAsStream(BESTAND)) {
            "$BESTAND niet op het classpath — draait deze test met demo-personas als afhankelijkheid?"
        }

        stroom.use { eigenschappen.load(it) }

        // Deze parser leest geen profiel-sleutels: stilzwijgend negeren zou een divergentie met
        // SmallRye opleveren die pas bij het starten van de demo blijkt.
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
