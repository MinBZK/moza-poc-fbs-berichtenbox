package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.common.exception.FbsFoutException
import nl.rijksoverheid.moz.fbs.common.exception.Foutcode
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.jboss.logging.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Telt de gestreamde bijlage-downloads op deze pod. Elk houdt een worker-thread en een connection
 * uit de bijlage-pool van het magazijn vast zolang de afnemer leest; zonder plafond kunnen trage
 * afnemers de worker-pool vullen en daarmee elk blocking endpoint van de dienst stilleggen. Een
 * totaal, en een klein plafond per ontvanger zodat één aanroeper de pod niet voor iedereen kan
 * vullen; wie erboven valt, krijgt een 503 met `Retry-After`. Zelfde opzet als de plafonds van
 * `_volgen`, en net als daar per pod.
 *
 * `@Startup` zodat een verkeerd ingestelde grens de pod bij het opstarten tegenhoudt en niet pas bij
 * de eerste grote download.
 */
@Startup
@ApplicationScoped
class Bijlagestreams(private val config: BijlageConfig) {

    private val open = AtomicInteger()

    private val openPerOntvanger = ConcurrentHashMap<Identificatienummer, Int>()

    init {
        require(!config.maxStilstand().isNegative && !config.maxStilstand().isZero) {
            "berichtenuitvraag.bijlage.max-stilstand (${config.maxStilstand()}) moet groter zijn dan 0"
        }

        // Vert.x krijgt de grens als int milliseconden.
        require(config.maxStilstand().toMillis() <= Int.MAX_VALUE) {
            "berichtenuitvraag.bijlage.max-stilstand (${config.maxStilstand()}) is te groot"
        }

        require(config.maxStreams() > 0) {
            "berichtenuitvraag.bijlage.max-streams (${config.maxStreams()}) moet groter zijn dan 0"
        }

        require(config.maxStreamsPerOntvanger() in 1..config.maxStreams()) {
            "berichtenuitvraag.bijlage.max-streams-per-ontvanger (${config.maxStreamsPerOntvanger()}) " +
                "moet tussen 1 en berichtenuitvraag.bijlage.max-streams (${config.maxStreams()}) liggen"
        }
    }

    /**
     * Reserveert een plek voor [ontvanger], of gooit een 503. De teruggegeven functie geeft de plek
     * vrij; een tweede aanroep doet niets, zodat elk pad dat de download beëindigt hem mag aanroepen.
     */
    fun reserveer(ontvanger: Identificatienummer): () -> Unit {
        if (open.incrementAndGet() > config.maxStreams()) {
            open.decrementAndGet()
            log.warnf("Plafond van %d gestreamde bijlage-downloads op deze pod bereikt; download geweigerd", config.maxStreams())

            throw teVeel()
        }

        // `merge` is in Java nullable, maar met een waarde en een optelling komt er nooit null uit.
        val eigen = checkNotNull(openPerOntvanger.merge(ontvanger, 1, Int::plus))

        if (eigen > config.maxStreamsPerOntvanger()) {
            geefVrij(ontvanger)
            log.infof(
                "Plafond van %d gestreamde bijlage-downloads per ontvanger op deze pod bereikt (ontvanger.type=%s)",
                config.maxStreamsPerOntvanger(),
                ontvanger.type,
            )

            throw teVeel()
        }

        val vrijgegeven = AtomicBoolean(false)

        return { if (vrijgegeven.compareAndSet(false, true)) geefVrij(ontvanger) }
    }

    internal val aantalOpen: Int get() = open.get()

    private fun geefVrij(ontvanger: Identificatienummer) {
        open.decrementAndGet()
        openPerOntvanger.computeIfPresent(ontvanger) { _, aantal -> (aantal - 1).takeIf { it > 0 } }
    }

    private fun teVeel() = FbsFoutException(
        Foutcode.TE_VEEL_GELIJKTIJDIGE_DOWNLOADS,
        Response.Status.SERVICE_UNAVAILABLE,
        "Er lopen te veel downloads tegelijk. Probeer het straks opnieuw.",
        retryAfterSeconden = RETRY_AFTER_SECONDEN,
    )

    private companion object {
        private val log: Logger = Logger.getLogger(Bijlagestreams::class.java)

        // Gelijk aan de overige 503's van deze dienst.
        const val RETRY_AFTER_SECONDEN = 30
    }
}
