package nl.rijksoverheid.moz.fbs.democonsole.personas

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.DemoConfig
import org.jboss.logging.Logger

/**
 * De ingerichte demo-identiteiten, één set voor zowel de keuzelijst als de berichtgenerator.
 * `@Startup` trekt de waardecontroles — het nummer, de bron, en of het magazijn bestaat — naar
 * boot-tijd; zonder dat blijkt een onbruikbare waarde pas bij de eerste aanroep, midden in een
 * demonstratie.
 */
@Startup
@ApplicationScoped
class PersonaService(config: DemoConfig) {

    private val personas: List<DemoPersona> = lees(config)

    fun alle(): List<DemoPersona> = personas

    /** De persona's waarvoor de generator berichten kan opvoeren: zonder organisatie geen bericht. */
    fun metMagazijnen(): List<DemoPersona> = personas.filter { it.magazijnen.isNotEmpty() }

    private fun lees(config: DemoConfig): List<DemoPersona> {
        val bekendeMagazijnen = config.magazijnen().keys
        val gelezen = config.personas()
            .map { (id, instelling) -> lees(id, instelling, bekendeMagazijnen) }
            // De volgorde van een configuratie-map volgt de hash van de sleutels, niet het bestand;
            // de id breekt de gelijkstand, zodat twee gelijke labels niet van plek wisselen.
            .sortedWith(compareBy({ it.label.lowercase() }, { it.id }))

        require(gelezen.isNotEmpty()) { "geen demo-persona ingericht onder demo.personas.*" }

        LOG.info("demo-persona's gelezen: ${gelezen.joinToString { it.id }}")

        return gelezen
    }

    private fun lees(id: String, instelling: DemoConfig.PersonaInstelling, bekendeMagazijnen: Set<String>): DemoPersona =
        try {
            val magazijnen = instelling.magazijnen().orElse(emptyList())

            magazijnen.forEach {
                require(it in bekendeMagazijnen) { "magazijn-OIN '$it' heeft geen demo.magazijnen-URL" }
            }

            DemoPersona(
                id = id,
                label = instelling.label(),
                type = instelling.type(),
                waarde = instelling.waarde(),
                magazijnen = magazijnen,
                bron = PersonaBron.van(instelling.bron()),
            )
        } catch (fout: IllegalArgumentException) {
            throw IllegalArgumentException("demo-persona '$id' is niet bruikbaar: ${fout.message ?: fout}", fout)
        }

    private companion object {

        val LOG: Logger = Logger.getLogger(PersonaService::class.java)
    }
}
