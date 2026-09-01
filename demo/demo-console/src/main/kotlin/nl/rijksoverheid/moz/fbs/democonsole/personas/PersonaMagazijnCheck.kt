package nl.rijksoverheid.moz.fbs.democonsole.personas

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.DemoConfig
import nl.rijksoverheid.moz.fbs.demopersonas.DemoPersona
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService

/**
 * De personadienst kent de identiteiten, deze module kent de magazijnen waar ze berichten voor
 * krijgen. Die twee moeten op elkaar aansluiten: wijst een persona naar een magazijn waarvoor hier
 * geen aanlever-URL staat, dan slaat de generator hem stil over en blijft zijn berichtenbox leeg.
 *
 * `@Startup`: dat hoort de module te laten weigeren te starten, niet halverwege een demonstratie
 * op te duiken.
 */
@Startup
@ApplicationScoped
class PersonaMagazijnCheck(personaService: PersonaService, config: DemoConfig) {

    init {
        vereisBekend(personaService.alle(), config.magazijnen().keys)
    }

    companion object {

        /**
         * Alles nalopen in plaats van bij de eerste opt-in stoppen: drie kapotte verwijzingen horen
         * geen drie herstarts te kosten.
         */
        fun vereisBekend(personas: List<DemoPersona>, bekendeMagazijnen: Set<String>) {
            val onbekend = personas.flatMap { persona ->
                persona.magazijnen.filterNot { it in bekendeMagazijnen }.map { persona.id to it }
            }

            if (onbekend.isEmpty()) return

            // Zonder ingericht magazijn wijst de melding naar de configuratie die ontbreekt; is er
            // wél inrichting, dan naar het OIN dat er niet in staat. Anders zoekt de lezer bij de
            // persona terwijl er niets met die persona mis is.
            val uitleg = if (bekendeMagazijnen.isEmpty()) {
                "er is geen magazijn ingericht onder demo.magazijnen"
            } else {
                onbekend.joinToString("; ") { (id, oin) -> "magazijn-OIN '$oin' van persona '$id' heeft geen demo.magazijnen-URL" }
            }

            throw IllegalArgumentException(uitleg)
        }
    }
}
