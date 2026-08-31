package nl.rijksoverheid.moz.fbs.magazijnsimulator

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Clock

/**
 * CDI-producer voor [Clock], zodat services geen statische `Instant.now()` gebruiken. Vanaf stap 3
 * hangt het gedrag van een magazijn aan de tijd, en dan is een klok die een test kan vastzetten het
 * verschil tussen een deterministische en een flakey suite.
 */
@Singleton
class ClockProducer {

    @Produces
    @ApplicationScoped
    fun clock(): Clock = Clock.systemUTC()
}
