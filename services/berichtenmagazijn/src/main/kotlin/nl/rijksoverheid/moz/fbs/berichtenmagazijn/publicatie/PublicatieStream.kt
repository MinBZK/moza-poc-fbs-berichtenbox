package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Outbox-poller voor de Publicatie Stream. Elke pollronde verwerkt tot
 * [PublicatieConfig.batchGrootte] claims; de feitelijke claim-en-aflever-
 * stap zit in [PublicatieClaimVerwerker], dat per claim een eigen
 * `REQUIRES_NEW`-transactie opent (zie daar voor onderbouwing).
 *
 * `every` is config-driven (tests kiezen via `%test` een snellere cadens; sub-1s clampt
 * Quarkus naar 1s). `concurrentExecution = SKIP` voorkomt parallelle rondes op dezelfde
 * instantie; `SKIP LOCKED` regelt parallelisme tussen instanties.
 */
@ApplicationScoped
class PublicatieStream(
    private val verwerker: PublicatieClaimVerwerker,
    private val config: PublicatieConfig,
) {

    private val log = Logger.getLogger(PublicatieStream::class.java)

    /**
     * Poison-pill counter: na N opeenvolgende mislukkingen slaat de stream één ronde over
     * zodat een kapotte claim of DB-uitval geen tight loop CPU/IO laat branden. Een `Error`
     * telt mee, ook al gaat die door naar de aanroeper. Reset bij elke succesvolle ronde. `AtomicInteger` zodat increment correct blijft mocht
     * `concurrentExecution` ooit `PROCEED` worden (`+=` op `@Volatile` is niet atomair).
     */
    private val opeenvolgendeFouten = AtomicInteger(0)

    @Scheduled(
        every = "{magazijn.publicatie.polling.interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun pollronde() {
        val huidig = opeenvolgendeFouten.get()
        if (huidig >= config.polling().maxOpeenvolgendeFouten()) {
            log.warnf(
                "Pollronde overgeslagen na %d opeenvolgende fouten — wacht op volgende interval (cooldown 1 ronde)",
                huidig,
            )
            // Reset zodat de volgende ronde een poging waagt; herhaling van
            // dezelfde fout zal de teller weer opvoeren.
            opeenvolgendeFouten.set(0)
            return
        }
        var verwerkt = 0
        val batchCap = config.batchGrootte()
        while (verwerkt < batchCap) {
            // Een onderbroken thread levert niets meer af: elke volgende claim zou direct op
            // de interrupt stranden en toch een poging plus een logregel kosten. De vlag
            // blijft staan voor de scheduler.
            if (Thread.currentThread().isInterrupted) {
                log.infof("Pollronde onderbroken na %d claims; resterende claims blijven openstaan", verwerkt)
                break
            }

            val isClaimVerwerkt = try {
                verwerker.verwerkEenClaim()
            } catch (ex: RuntimeException) {
                // Onverwachte fout (programmeerfout, DB-uitval, poison-claim): log + teller++,
                // niet door-gooien (dat deactiveert de scheduler-baan in sommige Quarkus-versies).
                val nieuw = opeenvolgendeFouten.incrementAndGet()
                log.errorf(
                    ex,
                    "Onverwachte fout in pollronde (opeenvolgendeFouten=%d, verwerkt=%d); ronde afgebroken",
                    nieuw, verwerkt,
                )
                return
            } catch (ex: Error) {
                // Hoort de JVM te bereiken, maar telt mee: een Error die zich bij dezelfde
                // claim herhaalt, zou anders elke ronde zonder cooldown terugkomen.
                opeenvolgendeFouten.incrementAndGet()
                throw ex
            }
            if (!isClaimVerwerkt) break
            verwerkt += 1
        }
        // Succesvolle ronde (≥0 claims, geen exception): poison-pill teller resetten.
        opeenvolgendeFouten.set(0)
        if (verwerkt > 0) {
            log.debugf("Pollronde verwerkt %d claims", verwerkt)
        }
    }
}
