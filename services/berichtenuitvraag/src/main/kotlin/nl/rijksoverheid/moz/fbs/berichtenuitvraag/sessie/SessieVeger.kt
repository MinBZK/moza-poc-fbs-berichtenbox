package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.OpruimUitkomst
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.Opruiming
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Ruimt periodiek de berichtgegevens op van ontvangers wier laatste sessie verlopen is, en legt
 * elke opruiming vast in het logboek: alleen de Redis-TTL als vangnet zou gegevens zonder logregel
 * laten verdwijnen.
 *
 * Draait op elke pod; de facade beslist per ontvanger atomair, dus een opruiming wordt maar één
 * keer gemeld. Gooit nooit: een mislukte ronde wordt bij de volgende tick herhaald.
 *
 * Eén tick vraagt batches op zolang ze vol zijn: na een piek aan verlopen sessies loopt de index zo
 * leeg in plaats van honderd per tick, en een batch vol mislukkers (die de facade naar achteren
 * schuift) houdt de ontvangers erachter niet tegen. Na een half interval begint de tick geen nieuwe
 * batch meer, zodat hij klaar is voor de volgende; wat over is, pakt die op.
 *
 * Komt een ontvanger binnen één tick terug, dan is het naar achteren schuiven zelf mislukt (bijvoorbeeld
 * een alleen-lezen Redis tijdens een failover) en zou de lus dezelfde batch tot het einde van het budget
 * herhalen, met evenveel logregels. De tick stopt dan; alleen een herhaling die nu wél gewist is, komt
 * alsnog in het logboek, want pas daarmee eindigt de verwerking.
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

    @Scheduled(every = "{berichtensessiecache.sessie.veeg-interval}", concurrentExecution = SKIP)
    fun veeg() {
        val einde = System.nanoTime() + budget.toNanos()
        val gezien = HashSet<Identificatienummer>()

        do {
            val opruimingen = try {
                sessiecache.ruimVerlopenSessiesOp(BATCH)
            } catch (e: Exception) {
                log.warnf("Verlopen sessies opruimen mislukt (%s); volgende ronde probeert opnieuw", e.javaClass.simpleName)

                return
            }

            val herhaald = opruimingen.filterNot { gezien.add(it.ontvanger) }.toSet()

            opruimingen
                .filter { it !in herhaald || it.uitkomst == OpruimUitkomst.GEWIST }
                .forEach(::legVast)

            if (herhaald.isNotEmpty()) {
                log.warnf("Veegronde gestopt: %d ontvangers kwamen in dezelfde ronde terug; volgende ronde probeert opnieuw", herhaald.size)

                return
            }
        } while (opruimingen.size == BATCH && System.nanoTime() < einde)
    }

    private fun legVast(opruiming: Opruiming) {
        sessieLogboek.opgeruimd(opruiming.ontvanger, OpruimAanleiding.VERLOPEN, opruiming.uitkomst)

        when (opruiming.uitkomst) {
            OpruimUitkomst.GEWIST -> Unit
            OpruimUitkomst.MISLUKT -> log.warnf("Opruimen verlopen sessie mislukt; volgende ronde probeert opnieuw")
            OpruimUitkomst.ONBEVESTIGD -> log.warnf(
                "Opruimen verlopen sessie niet bevestigd; de gegevens zijn mogelijk gewist, anders vangt de Redis-TTL ze op",
            )
        }
    }

    companion object {
        /** Gelijk aan het standaardmaximum van de facade. */
        const val BATCH = 100
    }
}
