package nl.rijksoverheid.moz.fbs.democonsole.generator

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Levert de gedeelde generator-configuratie als CDI-bean. De organisaties (één per magazijn)
 * en de persona-opt-ins staan hier centraal en moeten sporen met de profielservice-stubs
 * onder `wiremock/demo-profiel/` — anders faalt aanleveren met 403.
 */
class GeneratorProducer {

    @Produces
    @ApplicationScoped
    fun generator(): DemoBerichtGenerator =
        DemoBerichtGenerator(
            personas = listOf(
                Persona("J. Pietersen", "BSN", "999993653", listOf(RVO, BELASTINGDIENST)),
                Persona("Bakkerij De Vroege Vogel", "BSN", "123456782", listOf(RVO)),
                Persona("Garage Van Dijk B.V.", "KVK", "12345678", listOf(BELASTINGDIENST)),
            ),
            organisaties = mapOf(
                RVO to Organisatie(
                    RVO,
                    "RVO",
                    listOf(
                        "Subsidieaanvraag ontvangen",
                        "Beschikking SDE++",
                        "Controle mestboekhouding",
                        "Herinnering eHerkenning",
                    ),
                ),
                BELASTINGDIENST to Organisatie(
                    BELASTINGDIENST,
                    "Belastingdienst",
                    listOf(
                        "Voorlopige aanslag 2026",
                        "Definitieve aanslag 2025",
                        "Herinnering aangifte omzetbelasting",
                        "Teruggaaf inkomstenbelasting",
                    ),
                ),
            ),
            klok = Clock.systemUTC(),
        )

    private companion object {

        const val RVO = "00000001003214345000"
        const val BELASTINGDIENST = "00000001823288444000"
    }
}
