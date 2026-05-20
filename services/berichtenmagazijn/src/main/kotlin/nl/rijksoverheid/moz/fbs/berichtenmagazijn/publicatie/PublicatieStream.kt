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
 * `every = "{magazijn.publicatie.polling.interval}"` — Quarkus Scheduler
 * resolveert deze property bij start zodat tests via `%test`-profile een
 * snellere cadens kunnen kiezen. Sub-1s waarden worden door Quarkus
 * geclampt naar 1s (`SimpleScheduler$IntervalTrigger`).
 *
 * `concurrentExecution = SKIP`: als een ronde langer duurt dan het interval
 * start de volgende pollronde niet parallel op dezelfde instantie. Row-level
 * `SKIP LOCKED` regelt parallelisme tussen meerdere instanties.
 */
@ApplicationScoped
class PublicatieStream(
    private val verwerker: PublicatieClaimVerwerker,
    private val config: PublicatieConfig,
) {

    private val log = Logger.getLogger(PublicatieStream::class.java)

    /**
     * Poison-pill counter: na meerdere opeenvolgende mislukkingen zonder
     * succes-tussendoor stopt de stream tijdelijk met polls (één ronde
     * overslaan). Voorkomt dat één blijvend kapotte claim of een doorlopende
     * DB-uitval CPU/IO blijft branden in een tight loop. Reset op elke
     * succesvolle ronde.
     *
     * `AtomicInteger` i.p.v. `@Volatile Int` zodat increment-op-failure correct
     * blijft als `concurrentExecution` ooit naar `PROCEED` zou wijzigen — `+=`
     * op `@Volatile` is een non-atomic read-modify-write.
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
            val isClaimVerwerkt = try {
                verwerker.verwerkEenClaim()
            } catch (ex: RuntimeException) {
                // Onverwachte fout: programmeerfout, DB-uitval, of een
                // poison-claim die elke poging IllegalStateException gooit.
                // Log + verhoog teller; volgende ronde mag het opnieuw proberen
                // tot polling.maxOpeenvolgendeFouten. Niet door-gooien anders
                // deactiveert de scheduler de baan in sommige Quarkus-versies.
                val nieuw = opeenvolgendeFouten.incrementAndGet()
                log.errorf(
                    ex,
                    "Onverwachte fout in pollronde (opeenvolgendeFouten=%d, verwerkt=%d); ronde afgebroken",
                    nieuw, verwerkt,
                )
                return
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
