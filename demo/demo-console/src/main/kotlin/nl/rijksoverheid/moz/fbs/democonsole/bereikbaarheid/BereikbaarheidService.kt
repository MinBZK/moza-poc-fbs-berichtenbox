package nl.rijksoverheid.moz.fbs.democonsole.bereikbaarheid

import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.omgeving.OmgevingConfig

/**
 * `@Startup`, omdat de adressen bij het bouwen worden getoetst. Lazy gebouwd faalt een typefout pas
 * bij de eerste uitlezing, en dan elke vijf seconden opnieuw met een stacktrace in het log en een
 * chip op onbekend, in plaats van één keer bij het opstarten.
 */
@ApplicationScoped
@Startup
class BereikbaarheidService(config: BereikbaarheidConfig, omgeving: OmgevingConfig) {

    private val controle = Bereikbaarheidscontrole(teControleren(config, omgeving.simulator()))

    /** Per component of het zelf antwoordt, gesorteerd op naam. */
    fun status(): Map<String, Bereikbaarheid> = controle.controleer()

    /**
     * Ook zonder open paneel. Het platform zet een component soms minuten op nul exemplaren en
     * herstelt het daarna vanzelf; zonder deze ronde staat zo'n uitval alleen in het log als er
     * toevallig iemand naar het paneel keek. Nu staan uitval en herstel er altijd met tijdstip in.
     *
     * SKIP: een ronde duurt hooguit de timeout van de controle, maar een volgende hoort er dan niet
     * bovenop te komen.
     */
    @Scheduled(every = "{bereikbaarheid.controle-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun controleerOpDeAchtergrond() {
        controle.controleer()
    }
}

/**
 * Welke componenten deze omgeving controleert. Een leeg adres zet de controle uit, zoals een leeg
 * Toxiproxy-adres een proxy uitzet. De simulator telt alleen mee als de omgeving hem kent: zijn adres
 * heeft een default en staat dus altijd, en zonder deze toets toont een omgeving zonder simulator de
 * hele demo een rode chip.
 */
internal fun teControleren(config: BereikbaarheidConfig, simulator: Boolean): Map<String, String> =
    config.bereikbaarheid()
        .mapValues { (_, component) -> component.url().orElse("").trim() }
        .filter { (naam, adres) -> adres.isNotEmpty() && (simulator || naam != SIMULATOR) }

private const val SIMULATOR = "simulator"
