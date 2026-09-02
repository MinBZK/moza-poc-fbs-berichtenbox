package nl.rijksoverheid.moz.fbs.magazijnsimulator

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Clock

/**
 * CDI-producer voor [Clock], zodat services geen statische `Instant.now()` gebruiken.
 *
 * De tijdstippen van een bericht — wanneer het ontvangen is, wanneer de status voor het laatst
 * wijzigde — komen hiervandaan, en de seed leidt de zijne ervan af. Een test die op zo'n tijdstip
 * toetst, kan de klok daarmee vastzetten in plaats van rond een bewegend doel te asserteren.
 */
@Singleton
class ClockProducer {

    @Produces
    @ApplicationScoped
    fun clock(): Clock = Clock.systemUTC()
}
