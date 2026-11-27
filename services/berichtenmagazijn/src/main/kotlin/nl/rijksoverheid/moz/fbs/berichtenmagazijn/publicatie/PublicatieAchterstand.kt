package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * De achterstand van de outbox als meetwaarden: hoeveel aanmeldingen nog niet gepubliceerd zijn en
 * hoe lang de oudste al wacht, per status en bestemming. Een stilgevallen publicatie valt zo op
 * vóórdat ontvangers merken dat er geen meldingen meer komen.
 *
 * Periodiek geteld en niet per scrape: de telling is één `GROUP BY` over de tabel, en een scrape
 * mag de database niet belasten. Elke pod telt dezelfde tabel, dus over pods hoort `max()`, geen
 * `sum()`. De labels zijn begrensd: `status` is een van twee waarden, `doel` een geconfigureerde
 * bestemming of [DownstreamClient.ONBEKEND_DOEL] voor rijen met een vervallen key.
 */
@ApplicationScoped
class PublicatieAchterstand(
    private val registry: MeterRegistry,
    private val entityManager: EntityManager,
    private val config: PublicatieConfig,
    private val clock: Clock,
) {

    private val log = Logger.getLogger(PublicatieAchterstand::class.java)

    private val aantal = mutableMapOf<Sleutel, AtomicLong>()

    private val oudsteSeconden = mutableMapOf<Sleutel, AtomicLong>()

    private val gemetenOp = AtomicLong()

    fun registreer(@Observes start: StartupEvent) {
        val doelen = config.downstreams().keys + DownstreamClient.ONBEKEND_DOEL

        for (status in GETELD) {
            for (doel in doelen) {
                val sleutel = Sleutel(status, doel)
                val a = AtomicLong().also { aantal[sleutel] = it }
                val o = AtomicLong().also { oudsteSeconden[sleutel] = it }

                Gauge.builder("fbs.publicatie.achterstand", a) { it.get().toDouble() }
                    .description("Aanmeldingen in de outbox die niet gepubliceerd zijn")
                    .tags("status", status.name.lowercase(), "doel", doel)
                    .register(registry)
                Gauge.builder("fbs.publicatie.achterstand.oudste", o) { it.get().toDouble() }
                    .description("Leeftijd in seconden van de oudste aanmelding in die achterstand (0 = geen)")
                    .tags("status", status.name.lowercase(), "doel", doel)
                    .register(registry)
            }
        }

        Gauge.builder("fbs.publicatie.achterstand.gemeten.op", gemetenOp) { it.get().toDouble() }
            .description("Laatste geslaagde telling van de achterstand (seconden sinds 1970; 0 = nog geen)")
            .register(registry)
    }

    @Scheduled(
        every = "{magazijn.publicatie.achterstand.interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun tel() {
        // Een mislukte telling laat de vorige waarden staan; `gemeten.op` loopt dan achter, en
        // dat is het signaal. De volgende ronde probeert het opnieuw.
        try {
            zet(telRijen(), clock.instant())
        } catch (ex: PersistenceException) {
            log.warnf(ex, "Telling van de publicatie-achterstand mislukt; vorige waarden blijven staan")
        }
    }

    @Transactional
    internal fun telRijen(): List<Rij> {
        @Suppress("UNCHECKED_CAST")
        val rijen = entityManager
            .createNativeQuery(
                """
                SELECT status, doel, COUNT(*), MIN(aangemaakt_op)
                FROM publicatie_deliveries
                WHERE status IN ('TE_PUBLICEREN', 'MISLUKT')
                GROUP BY status, doel
                """.trimIndent(),
            )
            .resultList as List<Array<Any?>>

        return rijen.map { rij ->
            Rij(
                status = DeliveryStatus.valueOf(rij[0] as String),
                doel = rij[1] as String,
                aantal = (rij[2] as Number).toLong(),
                oudste = rij[3] as Instant,
            )
        }
    }

    /** Een combinatie zonder rijen gaat naar 0, anders blijft een opgeloste achterstand zichtbaar. */
    internal fun zet(rijen: List<Rij>, nu: Instant) {
        val perSleutel = rijen
            .groupBy { Sleutel(it.status, if (it.doel in config.downstreams()) it.doel else DownstreamClient.ONBEKEND_DOEL) }

        for ((sleutel, teller) in aantal) {
            val groep = perSleutel[sleutel].orEmpty()
            val oudste = groep.minOfOrNull { it.oudste }

            teller.set(groep.sumOf { it.aantal })
            oudsteSeconden.getValue(sleutel).set(oudste?.let { Duration.between(it, nu).seconds.coerceAtLeast(0) } ?: 0)
        }

        gemetenOp.set(nu.epochSecond)
    }

    internal data class Rij(val status: DeliveryStatus, val doel: String, val aantal: Long, val oudste: Instant)

    private data class Sleutel(val status: DeliveryStatus, val doel: String)

    private companion object {
        val GETELD = listOf(DeliveryStatus.TE_PUBLICEREN, DeliveryStatus.MISLUKT)
    }
}
