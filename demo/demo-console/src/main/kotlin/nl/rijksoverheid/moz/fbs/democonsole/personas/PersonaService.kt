package nl.rijksoverheid.moz.fbs.democonsole.personas

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.DemoConfig
import org.jboss.logging.Logger
import java.util.Locale

/**
 * De ingerichte demo-identiteiten, één set voor zowel de keuzelijst als de berichtgenerator.
 * `@Startup`: een fout in de inrichting hoort de module te laten weigeren te starten, niet
 * halverwege een demonstratie op te duiken.
 */
@Startup
@ApplicationScoped
class PersonaService(config: DemoConfig) {

    private val personas: List<DemoPersona> = lees(config)

    fun alle(): List<DemoPersona> = personas

    fun metMagazijnen(): List<DemoPersona> = personas.filter { it.magazijnen.isNotEmpty() }

    private fun lees(config: DemoConfig): List<DemoPersona> {
        val bekendeMagazijnen = config.magazijnen().keys

        require(bekendeMagazijnen.isNotEmpty()) { "geen magazijn ingericht onder demo.magazijnen.\"<OIN>\".url" }

        val gelezen = mutableListOf<DemoPersona>()
        val onbruikbaar = mutableListOf<Pair<String, IllegalArgumentException>>()

        // Alles nalopen in plaats van bij de eerste fout stoppen: de volgorde van een
        // configuratie-map volgt de hash van de sleutels, dus wie wél gemeld wordt zou anders
        // willekeurig zijn, en drie kapotte persona's kosten drie herstarts.
        config.personas().forEach { (id, instelling) ->
            try {
                gelezen += lees(id, instelling, bekendeMagazijnen)
            } catch (fout: IllegalArgumentException) {
                onbruikbaar += id to fout
            }
        }

        if (onbruikbaar.isNotEmpty()) {
            throw IllegalArgumentException(
                onbruikbaar.sortedBy { it.first }
                    .joinToString("\n", prefix = "onbruikbare demo-persona's:\n") { (id, fout) -> "  - $id: ${fout.message}" },
                onbruikbaar.first().second,
            )
        }

        require(gelezen.isNotEmpty()) { "geen demo-persona ingericht onder demo.personas.*" }

        // Zonder sortering volgt de keuzelijst de hash van de sleutels; de id breekt de gelijkstand,
        // zodat twee gelijke labels niet per boot van plek wisselen.
        val gesorteerd = gelezen.sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.id }))

        vereisUniekeOntvangers(gesorteerd)
        meld(gesorteerd)

        return gesorteerd
    }

    private fun lees(id: String, instelling: DemoConfig.PersonaInstelling, bekendeMagazijnen: Set<String>): DemoPersona {
        val magazijnen = instelling.magazijnen().orElse(emptyList())

        magazijnen.forEach {
            require(it.isNotBlank()) { "lege magazijn-OIN in de lijst (afsluitende komma?)" }
            require(it in bekendeMagazijnen) { "magazijn-OIN '$it' heeft geen demo.magazijnen-URL" }
        }

        return DemoPersona(
            id = id,
            label = instelling.label(),
            type = instelling.type(),
            waarde = instelling.waarde(),
            magazijnen = magazijnen,
            bron = PersonaBron.van(instelling.bron()),
        )
    }

    private fun vereisUniekeOntvangers(personas: List<DemoPersona>) {
        // Alleen de id's in de melding: het nummer zelf hoort niet in de log.
        val botsend = personas.groupBy { it.ontvanger }.values.filter { it.size > 1 }

        require(botsend.isEmpty()) {
            "demo-persona's delen een identificatienummer: " +
                botsend.joinToString("; ") { groep -> groep.joinToString(" en ") { it.id } }
        }
    }

    // Mét bron en aantal opt-ins: een persona zonder opt-in krijgt niets van de generator en toont
    // dus een lege lijst. Dat is een geldige inrichting (Grootbedrijf haalt op bij de stub-magazijnen),
    // maar bij een weggevallen regel is dit de enige plek waar het verschil te zien is.
    private fun meld(personas: List<DemoPersona>) {
        LOG.info("demo-persona's gelezen: " + personas.joinToString { "${it.id} (${it.bron.wire}, ${it.magazijnen.size} magazijn(en))" })
    }

    private companion object {

        val LOG: Logger = Logger.getLogger(PersonaService::class.java)
    }
}
