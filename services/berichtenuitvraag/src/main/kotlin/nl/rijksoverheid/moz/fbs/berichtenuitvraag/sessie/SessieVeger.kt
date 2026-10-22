package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.OpruimUitkomst
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.Opruiming
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ruimt periodiek de berichtgegevens op van ontvangers wier laatste sessie verlopen is, en legt
 * elke opruiming vast in het logboek: alleen de Redis-TTL als vangnet zou gegevens zonder logregel
 * laten verdwijnen. Elke opruiming komt in het logboek zodra de facade haar meldt, niet pas aan het
 * einde van een batch: stopt de pod halverwege, dan is wat al gewist is ook vastgelegd.
 *
 * Draait op elke pod; de facade beslist per ontvanger atomair, dus een opruiming wordt maar één
 * keer gemeld. Gooit nooit: een mislukte ronde wordt bij de volgende tick herhaald. Mislukt dat
 * [ALARM_NA] keer op rij, dan volgt een foutregel met [ALERT_TOKEN]: zonder veegtaak wist de
 * Redis-TTL alles zonder logregel.
 *
 * Eén tick vraagt batches op zolang de kandidatenbatch vol is: na een piek aan verlopen sessies loopt
 * de index zo leeg in plaats van honderd per tick, en een batch vol mislukkers (die de facade naar
 * achteren schuift) houdt de ontvangers erachter niet tegen. Het aantal opruimingen zegt daarover
 * niets: een kandidaat die een andere pod net veegde, wordt niet gemeld. Na een half interval begint
 * de tick geen nieuwe batch meer, zodat hij klaar is voor de volgende; wat over is, pakt die op.
 *
 * Komt een ontvanger binnen één tick terug, dan is het naar achteren schuiven zelf mislukt (bijvoorbeeld
 * een alleen-lezen Redis tijdens een failover) en zou de lus dezelfde batch tot het einde van het budget
 * herhalen, met evenveel logregels. De tick stopt dan na die batch; alleen een herhaling die nu wél
 * gewist is, komt alsnog in het logboek, want pas daarmee eindigt de verwerking.
 */
@ApplicationScoped
class SessieVeger(
    private val sessiecache: Sessiecache,
    private val sessieLogboek: SessieLogboek,
    @ConfigProperty(name = "berichtensessiecache.sessie.veeg-interval")
    veegInterval: Duration,
) {

    private val log = Logger.getLogger(SessieVeger::class.java)

    private val budget: Duration = veegInterval.dividedBy(2)

    private val mislukteRondesOpRij = AtomicInteger()

    @Scheduled(every = "{berichtensessiecache.sessie.veeg-interval}", concurrentExecution = SKIP)
    fun veeg() {
        val einde = System.nanoTime() + budget.toNanos()
        val gezien = HashSet<Any>()

        try {
            do {
                var herhaald = 0

                val vol = sessiecache.ruimVerlopenSessiesOp(BATCH) { opruiming ->
                    val nieuw = gezien.add(identiteit(opruiming))

                    if (!nieuw) herhaald++

                    if (nieuw || opruiming.uitkomst == OpruimUitkomst.GEWIST) legVast(opruiming)
                }

                if (herhaald > 0) {
                    log.warnf("Veegronde gestopt: %d ontvangers kwamen in dezelfde ronde terug; volgende ronde probeert opnieuw", herhaald)

                    break
                }
            } while (vol && !Thread.currentThread().isInterrupted && System.nanoTime() < einde)
        } catch (e: Exception) {
            rondeMislukt(e)

            return
        }

        mislukteRondesOpRij.set(0)
    }

    private fun rondeMislukt(e: Exception) {
        val opRij = mislukteRondesOpRij.incrementAndGet()

        // Niet elke ronde: bij een aanhoudende storing zou een regel per tick het alert laten ondersneeuwen.
        if (opRij >= ALARM_NA && (opRij - ALARM_NA) % ALARM_HERHALING == 0) {
            log.errorf(
                "%s %d veegrondes op rij mislukt (%s); verlopen sessies blijven staan tot de Redis-TTL ze zonder logregel wist",
                ALERT_TOKEN,
                opRij,
                e.javaClass.simpleName,
            )
        } else {
            log.warnf("Verlopen sessies opruimen mislukt (%s); volgende ronde probeert opnieuw", e.javaClass.simpleName)
        }
    }

    private fun identiteit(opruiming: Opruiming): Any = when (opruiming) {
        is Opruiming.VanOntvanger -> opruiming.ontvanger
        is Opruiming.ZonderOntvanger -> opruiming.kenmerk
    }

    private fun legVast(opruiming: Opruiming) {
        when (opruiming) {
            is Opruiming.VanOntvanger -> sessieLogboek.opgeruimd(opruiming.ontvanger, OpruimAanleiding.VERLOPEN, opruiming.uitkomst)
            // Zelfde token als een mislukte logregel: ook hier ontbreekt de regel voor een opruiming.
            is Opruiming.ZonderOntvanger -> log.errorf(
                "%s: opruimen niet in het logboek vastgelegd (foutId=%s, aanleiding=%s, uitkomst=%s, ontvanger.type=onbekend, " +
                    "oorzaak=zonder bekende ontvanger; de leesbare vorm was al verlopen)",
                SessieLogboek.ALERT_TOKEN,
                UUID.randomUUID(),
                OpruimAanleiding.VERLOPEN.waarde,
                opruiming.uitkomst,
            )
        }

        when (opruiming.uitkomst) {
            OpruimUitkomst.GEWIST -> Unit
            OpruimUitkomst.MISLUKT -> log.warnf("Opruimen verlopen sessie mislukt; volgende ronde probeert opnieuw")
            OpruimUitkomst.ONBEVESTIGD -> log.warnf(
                "Opruimen verlopen sessie niet bevestigd; de gegevens zijn mogelijk gewist, anders probeert een volgende ronde het opnieuw",
            )
        }
    }

    companion object {
        /** Gelijk aan het standaardmaximum van de facade. */
        const val BATCH = 100

        /** Stabiel token voor alert-routing: de veegtaak faalt aanhoudend. */
        const val ALERT_TOKEN = "[ALERT sessie_veger_faalt]"

        /** Zoveel mislukte rondes op rij voordat het alert volgt; één of twee zijn een hik. */
        const val ALARM_NA = 3

        /** Daarna elke zoveelste mislukte ronde opnieuw, tot er een slaagt. */
        const val ALARM_HERHALING = 10
    }
}
