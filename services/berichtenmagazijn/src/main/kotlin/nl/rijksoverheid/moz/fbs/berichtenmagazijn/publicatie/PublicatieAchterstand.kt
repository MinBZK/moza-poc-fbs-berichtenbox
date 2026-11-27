package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.narayana.jta.QuarkusTransactionException
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.jboss.logging.Logger
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * De achterstand van de outbox als meetwaarden: hoeveel aanmeldingen niet gepubliceerd zijn, en
 * hoe lang de oudste al had mogen gaan. Een stilgevallen publicatie valt zo op vóórdat ontvangers
 * merken dat er geen meldingen meer komen.
 *
 * `te_publiceren` telt alleen aanmeldingen waarvan het publicatietijdstip voorbij is: een bericht
 * dat volgende week gepubliceerd mag worden, is geen achterstand. De leeftijd loopt vanaf het moment
 * dat de aanmelding mocht gaan, ook tijdens een retry. Voor `mislukt` is er alleen het aantal: die
 * rijen zijn terminaal, dus een leeftijd zegt niets over nu.
 *
 * Periodiek geteld en niet per scrape, zodat een scrape de database niet raakt. Elke pod telt
 * dezelfde tabel, dus over pods hoort `max()`, geen `sum()`. De labels zijn begrensd: `status` is
 * een van twee waarden, `doel` een geconfigureerde bestemming of [DownstreamClient.ONBEKEND_DOEL].
 */
@ApplicationScoped
class PublicatieAchterstand(
    private val registry: MeterRegistry,
    private val entityManager: EntityManager,
    private val config: PublicatieConfig,
    private val clock: Clock,
) {

    private val log = Logger.getLogger(PublicatieAchterstand::class.java)

    // In de constructor, niet bij de start: de scheduler kan al tellen voordat [registreer] draait.
    private val doelen: Set<String> = config.downstreams().keys + DownstreamClient.ONBEKEND_DOEL

    private val aantal: Map<Sleutel, AtomicLong> =
        GETELD.flatMap { status -> doelen.map { Sleutel(status, it) } }.associateWith { AtomicLong() }

    private val oudsteSeconden: Map<String, AtomicLong> = doelen.associateWith { AtomicLong() }

    private val gemetenOp = AtomicLong()

    private val mislukkingenOpRij = AtomicInteger()

    fun registreer(@Observes start: StartupEvent) {
        for ((sleutel, waarde) in aantal) {
            Gauge.builder("fbs.publicatie.achterstand", waarde) { it.get().toDouble() }
                .description("Aanmeldingen in de outbox die niet gepubliceerd zijn")
                .tags("status", sleutel.status.name.lowercase(), "doel", sleutel.doel)
                .register(registry)
        }

        for ((doel, waarde) in oudsteSeconden) {
            Gauge.builder("fbs.publicatie.achterstand.oudste", waarde) { it.get().toDouble() }
                .description("Hoe lang de oudste te publiceren aanmelding al had mogen gaan (0 = geen)")
                .baseUnit(SECONDEN)
                .tags("status", "te_publiceren", "doel", doel)
                .register(registry)
        }

        Gauge.builder("fbs.publicatie.achterstand.gemeten.op", gemetenOp) { it.get().toDouble() }
            .description("Laatste geslaagde telling van de achterstand, sinds 1970 (0 = nog geen)")
            .baseUnit(SECONDEN)
            .register(registry)
    }

    @Scheduled(
        every = "{magazijn.publicatie.achterstand.interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun tel() = tel { QuarkusTransaction.requiringNew().call { telRijen() } }

    internal fun tel(teller: () -> List<Rij>) {
        // Een mislukte telling laat de vorige waarden staan; `gemeten.op` loopt dan achter, en
        // dat is het signaal. Een gesloten EntityManager bij het afsluiten valt onder dezelfde regel.
        try {
            zet(teller())
            mislukkingenOpRij.set(0)
        } catch (ex: PersistenceException) {
            meldMislukt(ex)
        } catch (ex: QuarkusTransactionException) {
            meldMislukt(ex)
        } catch (ex: IllegalStateException) {
            meldMislukt(ex)
        }
    }

    /** De eerste mislukking op rij met de volledige oorzaak; daarna, elke minuut per pod, alleen de kern. */
    private fun meldMislukt(ex: RuntimeException) {
        if (mislukkingenOpRij.getAndIncrement() == 0) {
            log.warnf(ex, "Telling van de publicatie-achterstand mislukt; vorige waarden blijven staan")
        } else {
            log.warnf("Telling van de publicatie-achterstand opnieuw mislukt: %s: %s", ex.javaClass.name, ex.message)
        }
    }

    /**
     * De query bevat geen parameters en geen persoonsgegevens. `now()` is de klok van de database;
     * `aangemaakt_op` komt van de pod die de rij plande, dus een klokverschil kan een leeftijd onder
     * nul geven. `GREATEST(0, ...)` vangt dat af.
     */
    internal fun telRijen(): List<Rij> {
        @Suppress("UNCHECKED_CAST")
        val rijen = entityManager
            .createNativeQuery(
                """
                SELECT d.status, d.doel, COUNT(*),
                       GREATEST(0, EXTRACT(EPOCH FROM now() - MIN(GREATEST(d.aangemaakt_op, b.publicatietijdstip))))
                FROM publicatie_deliveries d
                JOIN berichten b ON b.id = d.bericht_db_id
                WHERE d.status = 'MISLUKT'
                   OR (d.status = 'TE_PUBLICEREN' AND b.publicatietijdstip <= now())
                GROUP BY d.status, d.doel
                """.trimIndent(),
            )
            .resultList as List<Array<Any?>>

        return rijen.map { rij ->
            Rij(
                status = DeliveryStatus.valueOf(rij[0] as String),
                doel = rij[1] as String,
                aantal = (rij[2] as Number).toLong(),
                oudsteSeconden = (rij[3] as Number).toLong(),
            )
        }
    }

    /** Een combinatie zonder rijen gaat naar 0, anders blijft een opgeloste achterstand zichtbaar. */
    internal fun zet(rijen: List<Rij>) {
        val perSleutel = rijen.groupBy { Sleutel(it.status, if (it.doel in doelen) it.doel else DownstreamClient.ONBEKEND_DOEL) }

        for ((sleutel, waarde) in aantal) {
            val groep = perSleutel[sleutel].orEmpty()

            waarde.set(groep.sumOf { it.aantal })

            if (sleutel.status == DeliveryStatus.TE_PUBLICEREN) {
                oudsteSeconden.getValue(sleutel.doel).set(groep.maxOfOrNull { it.oudsteSeconden } ?: 0)
            }
        }

        gemetenOp.set(clock.instant().epochSecond)
    }

    internal data class Rij(val status: DeliveryStatus, val doel: String, val aantal: Long, val oudsteSeconden: Long)

    private data class Sleutel(val status: DeliveryStatus, val doel: String)

    private companion object {
        val GETELD = listOf(DeliveryStatus.TE_PUBLICEREN, DeliveryStatus.MISLUKT)

        /** Prometheus plakt de eenheid achter de naam: `..._oudste_seconds`. */
        const val SECONDEN = "seconds"
    }
}
