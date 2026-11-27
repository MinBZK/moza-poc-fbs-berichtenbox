package nl.rijksoverheid.moz.fbs.beheerconsole

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Clock

/** CDI-producer voor [Clock]: de geldigheid van het stelseldocument hangt aan de tijd. */
@Singleton
class ClockProducer {

    @Produces
    @ApplicationScoped
    fun clock(): Clock = Clock.systemUTC()
}
