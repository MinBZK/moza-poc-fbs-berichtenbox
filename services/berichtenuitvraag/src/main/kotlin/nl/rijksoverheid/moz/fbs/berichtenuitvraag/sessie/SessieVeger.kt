package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.OpruimUitkomst
import org.jboss.logging.Logger

/**
 * Ruimt periodiek de berichtgegevens op van ontvangers wier laatste sessie verlopen is, en legt
 * elke opruiming vast in het logboek: alleen de Redis-TTL als vangnet zou gegevens zonder logregel
 * laten verdwijnen.
 *
 * Draait op elke pod; de facade beslist per ontvanger atomair, dus een opruiming wordt maar één
 * keer gemeld. Gooit nooit: een mislukte ronde wordt bij de volgende tick herhaald.
 */
@ApplicationScoped
class SessieVeger(
    private val sessiecache: Sessiecache,
    private val sessieLogboek: SessieLogboek,
) {

    private val log = Logger.getLogger(SessieVeger::class.java)

    @Scheduled(every = "{berichtensessiecache.sessie.veeg-interval}", concurrentExecution = SKIP)
    fun veeg() {
        val opruimingen = try {
            sessiecache.ruimVerlopenSessiesOp()
        } catch (e: Exception) {
            log.warnf("Verlopen sessies opruimen mislukt (%s); volgende ronde probeert opnieuw", e.javaClass.simpleName)

            return
        }

        opruimingen.forEach { opruiming ->
            sessieLogboek.opgeruimd(opruiming.ontvanger, OpruimAanleiding.VERLOPEN, opruiming.uitkomst)

            when (opruiming.uitkomst) {
                OpruimUitkomst.GEWIST -> Unit
                OpruimUitkomst.MISLUKT -> log.warnf("Opruimen verlopen sessie mislukt; volgende ronde probeert opnieuw")
                OpruimUitkomst.ONBEVESTIGD -> log.warnf(
                    "Opruimen verlopen sessie niet bevestigd; de gegevens zijn mogelijk gewist, anders vangt de Redis-TTL ze op",
                )
            }
        }
    }
}
