package nl.rijksoverheid.moz.fbs.democonsole.personas

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.DemoConfig

/**
 * De ingerichte demo-identiteiten, één set voor zowel de keuzelijst als de berichtgenerator.
 * `@Startup` dwingt af dat een typfout in de configuratie de module laat weigeren te starten,
 * in plaats van pas midden in een demonstratie een 400 uit het magazijn op te leveren.
 */
@Startup
@ApplicationScoped
class PersonaService(config: DemoConfig) {

    private val personas: List<DemoPersona> = config.personas()
        .map { (id, instelling) -> lees(id, instelling) }
        // Een map uit de configuratie heeft geen betekenisvolle volgorde; zonder sortering
        // wisselt de keuzelijst per boot van volgorde.
        .sortedBy { it.label.lowercase() }

    fun alle(): List<DemoPersona> = personas

    /** De persona's waarvoor de generator berichten kan opvoeren: zonder organisatie geen bericht. */
    fun metMagazijnen(): List<DemoPersona> = personas.filter { it.magazijnen.isNotEmpty() }

    private fun lees(id: String, instelling: DemoConfig.PersonaInstelling): DemoPersona =
        try {
            DemoPersona(
                id = id,
                label = instelling.label(),
                type = instelling.type(),
                waarde = instelling.waarde(),
                magazijnen = instelling.magazijnen().orElse(emptyList()),
                bron = PersonaBron.van(instelling.bron()),
            )
        } catch (fout: IllegalArgumentException) {
            throw IllegalArgumentException("demo-persona '$id' is niet bruikbaar: ${fout.message}", fout)
        }
}
