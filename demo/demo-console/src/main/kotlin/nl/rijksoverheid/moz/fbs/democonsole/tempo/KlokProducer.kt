package nl.rijksoverheid.moz.fbs.democonsole.tempo

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/** Eén klokbron, zodat de duurgrens van de stroom te toetsen is zonder een uur te wachten. */
@ApplicationScoped
class KlokProducer {

    @Produces
    @ApplicationScoped
    fun clock(): Clock = Clock.systemUTC()
}
