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
 * Een ronde kan ook slagen terwijl het opruimen per ontvanger blijft mislukken, bijvoorbeeld op een
 * alleen-lezen Redis na een failover: de kandidaten zijn dan op te vragen, elke veeg faalt. Daarom
 * telt de veger ook per ontvanger de mislukte pogingen op rij. Zodra er een ontvanger [ALARM_NA]
 * keer op rij mislukte, volgt hetzelfde alert, één regel voor alle ontvangers samen, en daarna
 * gedempt zoals bij de rondes. Een opruiming zonder bekende ontvanger geeft per reeks mislukkingen
 * maar één keer het ontbrekende logboek aan, en nog eens als ze ten slotte lukt; anders kwam elk
 * uitstel op elke pod met een nieuwe foutId voor hetzelfde gat. Wat de veger zo onthoudt, is
 * begrensd ([MAX_GEVOLGD]) en wordt na [VERGEET_NA] zonder nieuwe mislukking vergeten: dan ruimde
 * meestal een andere pod hem al op.
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

    private val vergeetNa: Duration = maxOf(veegInterval.multipliedBy(VERGEET_NA_INTERVALLEN), VERGEET_NA)

    /** Per ontvanger (of kenmerk) de reeks mislukte pogingen; de oudst gebruikte valt als eerste weg. */
    private val reeksen = object : LinkedHashMap<Any, Reeks>(INITIEEL, LAADFACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Reeks>): Boolean = size > MAX_GEVOLGD
    }

    /** Rondes op rij waarin een ontvanger vastzat; stuurt de demping van het alert per ontvanger. */
    private var rondesVastgelopen = 0

    private class Reeks(var aantal: Int, var laatst: Long)

    // Synchronized: de scheduler draait de ronde nooit dubbel, maar de telling per ontvanger hoort ook
    // een aanroep van buiten de scheduler niet half te zien.
    @Synchronized
    @Scheduled(every = "{berichtensessiecache.sessie.veeg-interval}", concurrentExecution = SKIP)
    fun veeg() {
        val einde = System.nanoTime() + budget.toNanos()
        val gezien = HashSet<Any>()

        try {
            do {
                var herhaald = 0

                val vol = sessiecache.ruimVerlopenSessiesOp(BATCH) { opruiming ->
                    val identiteit = identiteit(opruiming)
                    val nieuw = gezien.add(identiteit)

                    if (!nieuw) herhaald++

                    // Eén poging per ronde telt; alleen een herhaling die nu wel gewist is, sluit de reeks af.
                    if (nieuw || opruiming.uitkomst == OpruimUitkomst.GEWIST) legVast(opruiming, boek(identiteit, opruiming.uitkomst))
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
        meldVastgelopen()
    }

    /** Voor tests: begint zonder reeksen, zodat ontvangers uit een eerdere test niet meetellen. */
    @Synchronized
    internal fun vergeetMislukkingen() {
        reeksen.clear()
        rondesVastgelopen = 0
    }

    @Synchronized
    internal fun aantalGevolgd(): Int = reeksen.size

    /** Boekt één poging; antwoordt hoeveel pogingen op rij er nu mislukt zijn, 0 na een geslaagde. */
    private fun boek(identiteit: Any, uitkomst: OpruimUitkomst): Int {
        if (uitkomst == OpruimUitkomst.GEWIST) {
            reeksen.remove(identiteit)

            return 0
        }

        val nu = System.nanoTime()
        val reeks = reeksen[identiteit]?.takeIf { nu - it.laatst <= vergeetNa.toNanos() } ?: Reeks(0, nu)

        reeks.aantal++
        reeks.laatst = nu
        reeksen[identiteit] = reeks

        return reeks.aantal
    }

    private fun meldVastgelopen() {
        val nu = System.nanoTime()

        reeksen.values.removeIf { nu - it.laatst > vergeetNa.toNanos() }

        val vast = reeksen.values.filter { it.aantal >= ALARM_NA }

        if (vast.isEmpty()) {
            rondesVastgelopen = 0

            return
        }

        // Zoals bij de rondes: na de eerste melding alleen elke zoveelste ronde, ook als er intussen
        // een ontvanger bijkomt; anders volgt bij een aanhoudende storing een regel per tick.
        val melden = rondesVastgelopen % ALARM_HERHALING == 0

        rondesVastgelopen++

        if (melden) {
            log.errorf(
                "%s %d ontvanger(s) konden %d of meer pogingen op rij niet opgeruimd worden (langste reeks %d); hun gegevens blijven staan tot de Redis-TTL ze zonder logregel wist",
                ALERT_TOKEN,
                vast.size,
                ALARM_NA,
                vast.maxOf { it.aantal },
            )
        }
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

    /** [opRij] is het aantal mislukte pogingen op rij na deze, 0 als ze lukte. */
    private fun legVast(opruiming: Opruiming, opRij: Int) {
        when (opruiming) {
            is Opruiming.VanOntvanger -> sessieLogboek.opgeruimd(opruiming.ontvanger, OpruimAanleiding.VERLOPEN, opruiming.uitkomst)
            // Zelfde token als een mislukte logregel: ook hier ontbreekt de regel voor een opruiming.
            is Opruiming.ZonderOntvanger -> if (opRij <= 1) {
                log.errorf(
                    "%s: opruimen niet in het logboek vastgelegd (foutId=%s, aanleiding=%s, uitkomst=%s, ontvanger.type=onbekend, " +
                        "oorzaak=zonder bekende ontvanger; de leesbare vorm was verlopen, onleesbaar of hoorde niet bij de index-entry)",
                    SessieLogboek.ALERT_TOKEN,
                    UUID.randomUUID(),
                    OpruimAanleiding.VERLOPEN.waarde,
                    opruiming.uitkomst,
                )
            }
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

        /** Zoveel ontvangers met een reeks mislukkingen onthoudt de veger hoogstens. */
        const val MAX_GEVOLGD = 1_000

        /**
         * Na zo lang zonder nieuwe mislukking is een reeks voorbij, of na vier veegintervallen als dat
         * langer is. Ruim boven het uitstel na een mislukking (minstens 30 s) plus een interval, zodat
         * een vastzittende ontvanger zijn reeks niet kwijtraakt.
         */
        val VERGEET_NA: Duration = Duration.ofMinutes(5)

        private const val VERGEET_NA_INTERVALLEN = 4L
        private const val INITIEEL = 16
        private const val LAADFACTOR = 0.75f
    }
}
