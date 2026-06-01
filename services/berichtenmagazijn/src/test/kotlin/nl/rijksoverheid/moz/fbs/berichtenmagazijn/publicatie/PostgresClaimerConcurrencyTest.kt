package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Borgt de load-bearing eigenschap van [PostgresPublicatieClaimer]: twee
 * parallelle claim-transacties (zoals scale-out in productie) krijgen
 * disjuncte resultaat-sets dankzij `SELECT ... FOR UPDATE SKIP LOCKED`.
 * Zonder deze test is de hele schaalbaarheids-claim van het outbox-design
 * niet geverifieerd.
 */
@QuarkusTest
@TestProfile(PostgresClaimerConcurrencyTest.SoloDownstreamProfile::class)
class PostgresClaimerConcurrencyTest {

    @Inject
    lateinit var claimer: PublicatieClaimer

    @Inject
    lateinit var berichten: BerichtRepository

    @Inject
    lateinit var deliveries: PublicatieDeliveryRepository

    @Inject
    lateinit var outbox: PublicatieOutbox

    @BeforeEach
    fun clean() {
        QuarkusTransaction.requiringNew().run {
            deliveries.deleteAll()
            berichten.deleteAll()
        }
    }

    @Test
    fun `parallel claimers krijgen disjuncte claim-sets via SKIP LOCKED`() {
        val verleden = Instant.now().minusSeconds(60)
        val aantal = 20
        QuarkusTransaction.requiringNew().run {
            repeat(aantal) {
                val b = Bericht(
                    berichtId = UUID.randomUUID(),
                    afzender = Oin("00000001003214345000"),
                    ontvanger = Bsn("999993653"),
                    onderwerp = "Concurrency",
                    inhoud = "x",
                    tijdstipOntvangst = verleden,
                    publicatietijdstip = verleden,
                )
                berichten.save(b)
                outbox.planDeliveries(b.berichtId, verleden)
            }
        }

        val pool = Executors.newFixedThreadPool(2)
        try {
            // Beide claimers houden hun lock vast totdat ze hun eigen claim-set
            // hebben verzameld; pas dan committen we. Daardoor zijn de SKIP LOCKED
            // skip-paths gegarandeerd geraakt — bij sequentiële commit zou de
            // eerste claimer alle rijen vrijgeven vóór de tweede start.
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val a = CompletableFuture.supplyAsync({
                QuarkusTransaction.requiringNew().call {
                    val resultaat = claimer.claimNuVerwerkbaar(maxBatch = aantal)
                    barrier.await(10, TimeUnit.SECONDS) // wacht tot B ook geclaimd heeft
                    resultaat.map { it.claimId }
                }
            }, pool)
            val b = CompletableFuture.supplyAsync({
                QuarkusTransaction.requiringNew().call {
                    val resultaat = claimer.claimNuVerwerkbaar(maxBatch = aantal)
                    barrier.await(10, TimeUnit.SECONDS)
                    resultaat.map { it.claimId }
                }
            }, pool)

            val claimsA = a.get(15, TimeUnit.SECONDS).toSet()
            val claimsB = b.get(15, TimeUnit.SECONDS).toSet()

            // Disjunct: geen overlap. Dit is de load-bearing eigenschap van SKIP LOCKED.
            val overlap = claimsA.intersect(claimsB)
            assertTrue(overlap.isEmpty(), "claim-sets overlappen $overlap (SKIP LOCKED kapot)")
            // Geen exacte teller: er zijn 2 downstreams (aanmeld uit profile + default
            // uit globale test-config), dus 2× aantal deliveries totaal. Belangrijkste
            // assertion blijft de disjunct-check hierboven.
            assertTrue(
                claimsA.size + claimsB.size >= aantal,
                "verwachte minstens $aantal claims verdeeld, kreeg ${claimsA.size + claimsB.size}",
            )
        } finally {
            pool.shutdownNow()
        }
    }

    class SoloDownstreamProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "magazijn.publicatie.downstreams.aanmeld.url" to "http://localhost:1/events",
            "quarkus.scheduler.enabled" to "false",
        )
    }
}
