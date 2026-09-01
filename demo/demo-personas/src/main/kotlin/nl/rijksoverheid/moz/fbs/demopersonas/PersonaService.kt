package nl.rijksoverheid.moz.fbs.demopersonas

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.Locale

/**
 * De ingerichte demo-identiteiten, één set voor zowel de keuzelijst als de berichtgenerator.
 * `@Startup`: een fout in de inrichting hoort de module te laten weigeren te starten, niet
 * halverwege een demonstratie op te duiken.
 */
@Startup
@ApplicationScoped
class PersonaService(config: PersonaConfig) {

    private val personas: List<DemoPersona> = lees(config)

    fun alle(): List<DemoPersona> = personas

    /** De set waarvoor de generator berichten opvoert: zonder magazijn geen aanlevering. */
    fun metMagazijnen(): List<DemoPersona> = personas.filter { it.magazijnen.isNotEmpty() }

    private fun lees(config: PersonaConfig): List<DemoPersona> {
        val gelezen = mutableListOf<DemoPersona>()
        val onbruikbaar = mutableListOf<Pair<String, Exception>>()

        // Alles nalopen in plaats van bij de eerste fout stoppen: de volgorde van een
        // configuratie-map volgt de hash van de sleutels, dus wie wél gemeld wordt zou anders
        // willekeurig zijn, en drie kapotte persona's kosten drie herstarts.
        config.personas().forEach { (id, instelling) ->
            try {
                gelezen += leesPersona(id, instelling)
            } catch (fout: IllegalArgumentException) {
                onbruikbaar += id to fout
            } catch (fout: IllegalStateException) {
                onbruikbaar += id to fout
            }
        }

        vereisBruikbaar(onbruikbaar)

        require(gelezen.isNotEmpty()) { "geen demo-persona ingericht onder demo.personas.*" }

        // Zonder sortering volgt de keuzelijst de hash van de sleutels; de id breekt de gelijkstand,
        // zodat twee gelijke labels niet per boot van plek wisselen.
        val gesorteerd = gelezen.sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.id }))

        vereisUniekeOntvangers(gesorteerd)

        LOG.info(logregel(gesorteerd))

        return gesorteerd
    }

    private fun leesPersona(id: String, instelling: PersonaConfig.PersonaInstelling): DemoPersona {
        val magazijnen = instelling.magazijnen().orElse(emptyList())

        // SmallRye trimt lijstwaarden niet, dus "OIN_A, OIN_B" levert een OIN met een spatie ervoor.
        // Of het OIN ook een ingericht magazijn is, weet deze dienst niet: die inrichting hoort bij
        // wie berichten aanlevert. De demo-console toetst dat bij het opstarten.
        magazijnen.forEach { require(it == it.trim()) { "magazijn-OIN '$it' heeft witruimte om zich heen" } }

        return DemoPersona(
            id = id,
            label = instelling.label(),
            type = instelling.type(),
            waarde = instelling.waarde(),
            magazijnen = magazijnen,
            bron = PersonaBron.van(instelling.bron()),
        )
    }

    private fun vereisBruikbaar(onbruikbaar: List<Pair<String, Exception>>) {
        if (onbruikbaar.isEmpty()) return

        // Dezelfde volgorde voor de melding en de bijgevoegde oorzaken; elke oorzaak houdt zijn
        // eigen persona-id, zodat er geen willekeurige tot dé oorzaak gepromoveerd wordt.
        val gesorteerd = onbruikbaar.sortedBy { it.first }

        val melding = gesorteerd.joinToString("\n", prefix = "onbruikbare demo-persona's:\n") { (id, oorzaak) ->
            "  - $id: ${oorzaak.message ?: oorzaak}"
        }

        val fout = IllegalArgumentException(melding)

        gesorteerd.forEach { (id, oorzaak) -> fout.addSuppressed(IllegalArgumentException("demo-persona '$id'", oorzaak)) }

        throw fout
    }

    private fun vereisUniekeOntvangers(gelezen: List<DemoPersona>) {
        val botsend = gelezen.groupBy { it.ontvanger }.values.filter { it.size > 1 }

        // Alleen id's en het type in de melding: de waarde hoort niet in de log.
        require(botsend.isEmpty()) {
            "demo-persona's delen een identificatienummer: " +
                botsend.joinToString("; ") { groep -> groep.joinToString(" en ") { it.id } + " (${groep.first().type})" }
        }
    }

    internal companion object {

        private val LOG: Logger = Logger.getLogger(PersonaService::class.java)

        /**
         * Mét bron en aantal magazijnen: dat aantal staat nergens anders in de runtime — het
         * personas-endpoint geeft het niet terug — terwijl een weggevallen `magazijnen`-regel de
         * generator deze persona stil laat overslaan. Nul is een geldige inrichting: Grootbedrijf
         * haalt op bij de stub-magazijnen zonder dat de generator voor hem aanlevert.
         */
        internal fun logregel(gelezen: List<DemoPersona>): String =
            "${gelezen.size} demo-persona's gelezen: " +
                gelezen.joinToString { "${it.id} (${it.bron.wire}, ${it.magazijnen.size} magazijn(en))" }
    }
}
