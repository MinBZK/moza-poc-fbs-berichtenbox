package nl.rijksoverheid.moz.fbs.magazijnsimulator.pool

import io.agroal.api.AgroalDataSource
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.util.logging.Logger

/**
 * Schrijft periodiek één regel over de connection pool.
 *
 * De simulator draait op een veel ruimere pool dan één magazijn nodig heeft — hij stelt er honderd
 * voor — terwijl de database van een gedeelde omgeving veel minder verbindingen toelaat. Zonder deze
 * regel is niet te zien of dat verschil ergens knelt: een aanvraag die op een connection wacht is
 * van buiten niet te onderscheiden van een magazijn dat traag antwoordt.
 *
 * De metingen komen uit Agroal zelf en vragen `quarkus.datasource.jdbc.metrics.enabled`; zonder die
 * vlag geeft elke teller nul terug.
 */
@ApplicationScoped
class Poolmonitor(private val bron: AgroalDataSource) {

    private val log = Logger.getLogger(Poolmonitor::class.java.name)

    private var vorige: Poolmoment? = null

    /** De tellers van dit moment. Los van het loggen, zodat een test ze kan bekijken. */
    fun meet(): Poolmoment {
        val metingen = bron.metrics

        return Poolmoment(
            inGebruik = metingen.activeCount(),
            vrij = metingen.availableCount(),
            wachtend = metingen.awaitingCount(),
            max = bron.configuration.connectionPoolConfiguration().maxSize(),
            piek = metingen.maxUsedCount(),
            opgezet = metingen.creationCount(),
            vernietigd = metingen.destroyCount(),
            wachtenGemiddeld = metingen.blockingTimeAverage(),
            wachtenLangst = metingen.blockingTimeMax(),
            wachtenTotaal = metingen.blockingTimeTotal(),
        )
    }

    @Scheduled(
        every = "{magazijnsimulator.pool.log-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun log() {
        val moment = meet()

        if (moment.verschiltVan(vorige)) {
            log.info(moment.regel())
        }

        vorige = moment
    }
}
