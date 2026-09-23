package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Het doorgeven van aanmeldingen tussen pods en het verlengen van een gevolgde sessie, tegen een
 * echte Redis. Een tweede, los gebouwde [RedisAanmeldingen] speelt de andere pod: die heeft zijn
 * eigen abonnement, precies zoals een tweede replica.
 */
@QuarkusTest
@TestProfile(RealRedisTestProfile::class)
class RedisAanmeldingenIntegrationTest {

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @Inject
    internal lateinit var aanmeldingen: Aanmeldingen

    @Inject
    internal lateinit var berichtenCache: BerichtenCache

    private val anderePod by lazy { RedisAanmeldingen(redis, STANDAARD_CONTROLE) }

    // Uniek per test: de Redis-container wordt gedeeld tussen testklassen met dit profiel.
    private val ontvanger = Oin(System.nanoTime().toString().padStart(20, '0').takeLast(20))
    private val cacheKey get() = BerichtenCache.cacheKey(ontvanger)

    @AfterEach
    fun stopAnderePod() {
        anderePod.stop()
    }

    @Test
    fun `een aanmelding op de ene pod komt aan bij de luisteraar op de andere`() {
        val ontvangen = LinkedBlockingQueue<UUID>()
        anderePod.registreer(cacheKey, { ontvangen += it }, {})
        anderePod.actief().await().atMost(WACHTTIJD)
        val berichtId = UUID.randomUUID()

        aanmeldingen.meld(cacheKey, berichtId).await().atMost(WACHTTIJD)

        assertEquals(berichtId, ontvangen.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `alleen de luisteraars van dezelfde sessie krijgen de aanmelding`() {
        val eigen = LinkedBlockingQueue<UUID>()
        val vreemd = LinkedBlockingQueue<UUID>()
        anderePod.registreer(cacheKey, { eigen += it }, {})
        anderePod.registreer("${cacheKey}x", { vreemd += it }, {})
        anderePod.actief().await().atMost(WACHTTIJD)

        aanmeldingen.meld(cacheKey, UUID.randomUUID()).await().atMost(WACHTTIJD)

        assertTrue(eigen.poll(5, TimeUnit.SECONDS) != null)
        assertTrue(vreemd.isEmpty())
    }

    @Test
    fun `een onleesbaar kanaalbericht wordt overgeslagen zonder het abonnement te breken`() {
        val ontvangen = LinkedBlockingQueue<UUID>()
        anderePod.registreer(cacheKey, { ontvangen += it }, {})
        anderePod.actief().await().atMost(WACHTTIJD)
        val pubsub = redis.pubsub(String::class.java)

        listOf("zonder-scheiding", " ${UUID.randomUUID()}", "$cacheKey geen-uuid").forEach {
            pubsub.publish(RedisAanmeldingen.KANAAL, it).await().atMost(WACHTTIJD)
        }

        val berichtId = UUID.randomUUID()
        aanmeldingen.meld(cacheKey, berichtId).await().atMost(WACHTTIJD)

        assertEquals(berichtId, ontvangen.poll(5, TimeUnit.SECONDS))
        assertTrue(ontvangen.isEmpty())
    }

    @Test
    fun `na afmelden komt er niets meer door`() {
        val ontvangen = LinkedBlockingQueue<UUID>()
        val afmelding = anderePod.registreer(cacheKey, { ontvangen += it }, {})
        anderePod.actief().await().atMost(WACHTTIJD)

        afmelding.afmelden()
        aanmeldingen.meld(cacheKey, UUID.randomUUID()).await().atMost(WACHTTIJD)

        assertEquals(null, ontvangen.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `wie registreert terwijl een ander afmeldt, blijft bereikbaar`() {
        // Het venster: één tabblad sluit terwijl een ander voor dezelfde ontvanger opent. De
        // afmelding haalt de set weg zodra hij leeg is; een registratie die op die set had gerekend
        // viel vroeger in het niets. Herhaald, omdat een race zich niet op commando laat zien.
        val pod = RedisAanmeldingen(redis, STANDAARD_CONTROLE)

        repeat(HERHALINGEN) { ronde ->
            val sleutel = "$cacheKey-$ronde"
            val vertrekkend = pod.registreer(sleutel, {}, {})
            val start = CyclicBarrier(2)
            val ontvangen = LinkedBlockingQueue<UUID>()

            val sluiter = Thread {
                start.await()
                vertrekkend.afmelden()
            }.apply { start() }

            start.await()
            val nieuw = pod.registreer(sleutel, { ontvangen += it }, {})
            sluiter.join()

            val berichtId = UUID.randomUUID()
            pod.verdeel("$sleutel $berichtId")

            assertEquals(berichtId, ontvangen.poll(), "ronde $ronde: de nieuwe luisteraar kreeg de aanmelding niet")
            nieuw.afmelden()
        }
    }

    @Test
    fun `een luisteraar die gooit, houdt het bericht niet weg bij de andere`() {
        val pod = RedisAanmeldingen(redis, STANDAARD_CONTROLE)
        val ontvangen = LinkedBlockingQueue<UUID>()
        pod.registreer(cacheKey, { throw IllegalStateException("tabblad kapot") }, {})
        pod.registreer(cacheKey, { ontvangen += it }, {})
        val berichtId = UUID.randomUUID()

        pod.verdeel("$cacheKey $berichtId")

        assertEquals(berichtId, ontvangen.poll())
    }

    @Test
    fun `een stoppende pod laat elke gevolgde sessie weten dat hij opnieuw moet verbinden`() {
        val pod = RedisAanmeldingen(redis, STANDAARD_CONTROLE)
        val storingen = LinkedBlockingQueue<Throwable>()
        pod.registreer(cacheKey, {}, { storingen += it })
        pod.registreer("${cacheKey}x", {}, { storingen += it })

        pod.stop()

        assertEquals(2, storingen.size, "elke luisteraar hoort het te horen, op elke sleutel")
    }

    @Test
    fun `een gezond abonnement doorstaat zijn controles zonder vals alarm`() {
        val pod = RedisAanmeldingen(redis, SNELLE_CONTROLE)
        val storingen = LinkedBlockingQueue<Throwable>()
        val ontvangen = LinkedBlockingQueue<UUID>()
        pod.registreer(cacheKey, { ontvangen += it }, { storingen += it })
        pod.actief().await().atMost(WACHTTIJD)

        // Ruim meer dan een handvol controles laten verstrijken.
        Thread.sleep(SNELLE_CONTROLE.toMillis() * 8)

        assertTrue(storingen.isEmpty(), "een gezond abonnement mag geen storing melden: $storingen")

        val berichtId = UUID.randomUUID()
        aanmeldingen.meld(cacheKey, berichtId).await().atMost(WACHTTIJD)

        assertEquals(berichtId, ontvangen.poll(5, TimeUnit.SECONDS))
        pod.stop()
    }

    @Test
    fun `een abonnement dat stil vastloopt, wordt opgemerkt en daarna herbouwd`() {
        // CLIENT PAUSE WRITE laat Redis elke PUBLISH vasthouden: de verbinding blijft open en er
        // komt geen fout, maar er komt ook niets meer door. Precies de stille storing waar de
        // periodieke probe voor is.
        val pod = RedisAanmeldingen(redis, SNELLE_CONTROLE)
        val storingen = LinkedBlockingQueue<Throwable>()
        pod.registreer(cacheKey, {}, { storingen += it })
        pod.actief().await().atMost(WACHTTIJD)

        redis.execute("CLIENT", "PAUSE", PAUZE.toMillis().toString(), "WRITE").await().atMost(WACHTTIJD)

        val storing = storingen.poll(PAUZE.toSeconds(), TimeUnit.SECONDS)

        assertTrue(storing is TimeoutException, "verwacht een verlopen probe, kreeg: $storing")

        // Na de pauze bouwt de volgende activering een nieuw abonnement dat weer doorgeeft.
        Thread.sleep(PAUZE.toMillis())
        val ontvangen = LinkedBlockingQueue<UUID>()
        pod.registreer(cacheKey, { ontvangen += it }, {})
        pod.actief().await().atMost(WACHTTIJD)
        val berichtId = UUID.randomUUID()

        aanmeldingen.meld(cacheKey, berichtId).await().atMost(WACHTTIJD)

        assertEquals(berichtId, ontvangen.poll(5, TimeUnit.SECONDS))
        pod.stop()
    }

    @Test
    fun `verlengSessie zonder sessie is false`() {
        assertFalse(berichtenCache.verlengSessie(cacheKey).await().atMost(WACHTTIJD))
    }

    @Test
    fun `verlengSessie laat een lopende ophaling op haar vangnet-TTL staan`() {
        berichtenCache.updateAggregationStatus(cacheKey, AggregationStatus(OphalenStatus.BEZIG, totaalMagazijnen = 1))
            .await().atMost(WACHTTIJD)
        val voor = pttl("$cacheKey:status")

        assertTrue(berichtenCache.verlengSessie(cacheKey).await().atMost(WACHTTIJD))
        assertTrue(pttl("$cacheKey:status") <= voor)
    }

    @Test
    fun `verlengSessie verlengt pas na de helft van de sessieduur, en dan ook de berichten`() {
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000001003214345000",
            afzenderNaam = "Magazijn A",
            ontvanger = ontvanger,
            onderwerp = "Oud bericht",
            publicatietijdstip = Instant.parse("2026-09-21T10:00:00Z"),
            magazijnId = "00000001003214345000",
            aantalBijlagen = 0,
        )
        berichtenCache.store(cacheKey, listOf(bericht)).await().atMost(WACHTTIJD)
        berichtenCache.storeAggregationStatus(cacheKey, AggregationStatus(OphalenStatus.GEREED, totaalMagazijnen = 1, geslaagd = 1))
            .await().atMost(WACHTTIJD)
        val hashKey = BerichtenCache.berichtKey(bericht.berichtId)

        // Vers: ruim boven de helft van de 2 s uit het profiel, dus niets te doen.
        assertTrue(berichtenCache.verlengSessie(cacheKey).await().atMost(WACHTTIJD))
        val versResterend = pttl(hashKey)

        Thread.sleep(1_200)
        assertTrue(pttl(hashKey) < 1_000)

        assertTrue(berichtenCache.verlengSessie(cacheKey).await().atMost(WACHTTIJD))

        listOf(hashKey, "$cacheKey:list", "$cacheKey:status").forEach { sleutel ->
            assertTrue(pttl(sleutel) > 1_500, "$sleutel niet verlengd")
        }

        assertTrue(versResterend > 1_000)
    }

    private fun pttl(sleutel: String): Long = redis.key().pttl(sleutel).await().atMost(WACHTTIJD)

    private companion object {
        val WACHTTIJD: Duration = Duration.ofSeconds(5)
        const val HERHALINGEN = 2_000
        val STANDAARD_CONTROLE: Duration = Duration.ofSeconds(30)
        val SNELLE_CONTROLE: Duration = Duration.ofMillis(200)

        // Langer dan het probevenster van 5 s, zodat een probe gegarandeerd verloopt.
        val PAUZE: Duration = Duration.ofSeconds(8)
    }
}
