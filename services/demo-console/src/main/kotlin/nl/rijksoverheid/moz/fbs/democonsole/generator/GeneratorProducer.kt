package nl.rijksoverheid.moz.fbs.democonsole.generator

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Levert de gedeelde generator-configuratie (personas, afzender-OIN, magazijnen) als
 * CDI-bean. De personas en de geautoriseerde afzender-OIN staan hier centraal, zodat de
 * demo op één plek aangepast wordt.
 */
class GeneratorProducer {

    @Produces
    @ApplicationScoped
    fun generator(): DemoBerichtGenerator =
        DemoBerichtGenerator(
            personas = listOf(
                Persona("J. Pietersen", "BSN", "999993653"),
                Persona("Bakkerij De Vroege Vogel", "BSN", "123456782"),
                Persona("Garage Van Dijk B.V.", "KVK", "12345678"),
            ),
            afzenderOin = "00000001003214345000",
            magazijnOins = listOf("00000001003214345000", "00000001823288444000"),
            klok = Clock.systemUTC(),
        )
}
