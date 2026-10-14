package nl.rijksoverheid.moz.fbs.stelselregister

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Clock

/**
 * CDI-producer voor [Clock]. Uitgifte en geldigheid van het stelseldocument hangen aan de tijd;
 * een test vervangt deze bean om verlopen en verversen deterministisch te maken.
 */
@Singleton
class ClockProducer {

    @Produces
    @ApplicationScoped
    fun clock(): Clock = Clock.systemUTC()
}
