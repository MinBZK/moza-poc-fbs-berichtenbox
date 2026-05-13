package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Clock

/**
 * CDI-producer voor [Clock]. Hiermee gebruiken services geen statische
 * `Instant.now()` of `Clock.systemUTC()` — die zijn lastig te overschrijven
 * in tests. Een test-profile kan een [io.quarkus.test.Mock]'d Clock injecteren
 * om publicatiedatum-/backoff-gedrag deterministisch te testen.
 */
@Singleton
class ClockProducer {

    @Produces
    @ApplicationScoped
    fun clock(): Clock = Clock.systemUTC()
}
