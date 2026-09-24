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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
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
        anderePod.registreer(cacheKey, { ontvangen += it }, {}, {})
        anderePod.actief().await().atMost(WACHTTIJD)
        val berichtId = UUID.randomUUID()

        aanmeldingen.meld(cacheKey, berichtId).await().atMost(WACHTTIJD)

        assertEquals(berichtId, ontvangen.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `alleen de luisteraars van dezelfde sessie krijgen de aanmelding`() {
        val eigen = LinkedBlockingQueue<UUID>()
        val vreemd = LinkedBlockingQueue<UUID>()
        anderePod.registreer(cacheKey, { eigen += it }, {}, {})
        anderePod.registreer("${cacheKey}x", { vreemd += it }, {}, {})
        anderePod.actief().await().atMost(WACHTTIJD)

        aanmeldingen.meld(cacheKey, UUID.randomUUID()).await().atMost(WACHTTIJD)

        assertTrue(eigen.poll(5, TimeUnit.SECONDS) != null)
        assertTrue(vreemd.isEmpty())
    }

    @Test
    fun `een onleesbaar kanaalbericht wordt overgeslagen zonder het abonnement te breken`() {
        val ontvangen = LinkedBlockingQueue<UUID>()
        anderePod.registreer(cacheKey, { ontvangen += it }, {}, {})
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
        val afmelding = anderePod.registreer(cacheKey, { ontvangen += it }, {}, {})
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
            val vertrekkend = pod.registreer(sleutel, {}, {}, {})
            val start = CyclicBarrier(2)
            val ontvangen = LinkedBlockingQueue<UUID>()

            val sluiter = Thread {
                start.await()
                vertrekkend.afmelden()
            }.apply { start() }

            start.await()
            val nieuw = pod.registreer(sleutel, { ontvangen += it }, {}, {})
            sluiter.join()

            val berichtId = UUID.randomUUID()
            pod.verdeel("$sleutel $berichtId")

            assertEquals(berichtId, ontvangen.poll(), "ronde $ronde: de nieuwe luisteraar kreeg de aanmelding niet")
            nieuw.afmelden()
        }
    }

    @Test
    fun `een luisteraar die gooit, houdt het bericht niet weg bij de andere`() {
        // Afwisselend gooiend en ontvangend: de volgorde in de set ligt niet vast, dus met één van
        // elk zou de test op een defecte verdeling de helft van de keren toch slagen.
        val pod = RedisAanmeldingen(redis, STANDAARD_CONTROLE)
        val ontvangen = LinkedBlockingQueue<UUID>()

        repeat(TABBLADEN) {
            pod.registreer(cacheKey, { throw IllegalStateException("tabblad kapot") }, {}, {})
            pod.registreer(cacheKey, { ontvangen += it }, {}, {})
        }

        val berichtId = UUID.randomUUID()

        pod.verdeel("$cacheKey $berichtId")

        assertEquals(List(TABBLADEN) { berichtId }, ontvangen.toList())
    }

    @Test
    fun `een stoppende pod laat elke gevolgde sessie opnieuw verbinden, zonder storing`() {
        val pod = RedisAanmeldingen(redis, STANDAARD_CONTROLE)
        val beeindigd = LinkedBlockingQueue<String>()
        val storingen = LinkedBlockingQueue<Throwable>()
        pod.registreer(cacheKey, {}, { storingen += it }, { beeindigd += "eigen" })
        pod.registreer("${cacheKey}x", {}, { storingen += it }, { beeindigd += "ander" })
        pod.actief().await().atMost(WACHTTIJD)

        pod.stop()

        assertEquals(setOf("eigen", "ander"), beeindigd.toSet(), "elke luisteraar hoort het te horen, op elke sleutel")

        // Het afmelden van het abonnement eindigt asynchroon; ook dat mag geen storing worden.
        assertEquals(null, storingen.poll(AFWIKKELING.toMillis(), TimeUnit.MILLISECONDS), "een uitrol is geen storing")
    }

    @Test
    fun `een stream die opent terwijl de pod stopt, eindigt meteen en abonneert niet opnieuw`() {
        val pod = RedisAanmeldingen(redis, STANDAARD_CONTROLE)
        pod.stop()
        val beeindigd = LinkedBlockingQueue<Unit>()

        pod.registreer(cacheKey, {}, {}, { beeindigd += Unit })

        assertEquals(1, beeindigd.size)

        // Een luisteraar die bij dat inlichten gooit, laat registreren toch slagen: anders krijgt de
        // aanroeper geen afmelding en blijft de luisteraar staan.
        pod.registreer(cacheKey, {}, {}, { throw IllegalStateException("stream al dicht") }).afmelden()

        assertThrows<AbonnementGesloten> { pod.actief().await().atMost(WACHTTIJD) }
    }

    @Test
    fun `een pod die stopt terwijl hij abonneert, laat geen abonnement achter`() {
        // Het abonnement komt pas na het stoppen binnen. Het hoort dan bij niemand meer en zou een
        // connection openhouden, met een controle die daarna nog doorloopt.
        val pod = RedisAanmeldingen(redis, SNELLE_CONTROLE)
        val voor = stabieleAbonnees()

        pod.actief().subscribe().with({}, {})
        pod.stop()

        // Het abonnement mag even bestaan: het komt pas na het stoppen binnen en wordt dan afgemeld.
        // Daarna hoort de telling terug te zijn, en te blijven.
        val einde = System.nanoTime() + AFWIKKELING.toNanos()

        while (abonnees() > voor && System.nanoTime() < einde) Thread.sleep(PEILING.toMillis())

        // Hooguit gelijk: een abonnement van een andere pod in dezelfde Redis mag intussen weg zijn.
        assertTrue(abonnees() <= voor, "abonnement achtergebleven na stop")

        Thread.sleep(AFWIKKELING.toMillis())

        assertTrue(abonnees() <= voor, "abonnement na stop alsnog opnieuw opgebouwd")
    }

    @Test
    fun `een Redis die even hapert, breekt de gevolgde sessies niet af`() {
        // Korter dan twee probes: de eerste verloopt, de tweede komt na de pauze gewoon terug. Eén
        // trage reactie mag niet alle streams van de pod tegelijk laten herverbinden.
        val pod = RedisAanmeldingen(redis, SNELLE_CONTROLE)
        val storingen = LinkedBlockingQueue<Throwable>()
        val ontvangen = LinkedBlockingQueue<UUID>()

        try {
            pod.registreer(cacheKey, { ontvangen += it }, { storingen += it }, {})
            pod.actief().await().atMost(WACHTTIJD)

            pauzeer(HAPERING)

            assertEquals(null, storingen.poll(TWEE_PROBES.toMillis(), TimeUnit.MILLISECONDS), "een enkele gemiste probe is geen storing")

            val berichtId = UUID.randomUUID()
            aanmeldingen.meld(cacheKey, berichtId).await().atMost(WACHTTIJD)

            assertEquals(berichtId, ontvangen.poll(5, TimeUnit.SECONDS))
        } finally {
            hervat()
            pod.stop()
        }
    }

    @Test
    fun `een abonnement dat stil vastloopt, wordt opgemerkt en daarna herbouwd`() {
        // CLIENT PAUSE WRITE laat Redis elke PUBLISH vasthouden: de connection blijft open en er
        // komt geen fout, maar er komt ook niets meer door. Precies de stille storing waar de
        // periodieke probe voor is.
        val pod = RedisAanmeldingen(redis, SNELLE_CONTROLE)
        val storingen = LinkedBlockingQueue<Throwable>()

        try {
            pod.registreer(cacheKey, {}, { storingen += it }, {})
            pod.actief().await().atMost(WACHTTIJD)

            val start = System.nanoTime()
            pauzeer(PAUZE)
            val storing = storingen.poll(PAUZE.toSeconds(), TimeUnit.SECONDS)
            val verstreken = Duration.ofNanos(System.nanoTime() - start)

            assertTrue(storing is TimeoutException, "verwacht een verlopen probe, kreeg: $storing")
            // Met marge: de eerste probe kan net vóór de pauze zijn verstuurd.
            assertTrue(verstreken >= TWEE_PROBES_ONDERGRENS, "al na één gemiste probe opgegeven ($verstreken)")

            // Na de pauze bouwt de volgende activering een nieuw abonnement dat weer doorgeeft.
            hervat()
            val ontvangen = LinkedBlockingQueue<UUID>()
            pod.registreer(cacheKey, { ontvangen += it }, {}, {})
            pod.actief().await().atMost(WACHTTIJD)
            val berichtId = UUID.randomUUID()

            aanmeldingen.meld(cacheKey, berichtId).await().atMost(WACHTTIJD)

            assertEquals(berichtId, ontvangen.poll(5, TimeUnit.SECONDS))
        } finally {
            hervat()
            pod.stop()
        }
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

    private fun abonnees(): Long =
        redis.execute("PUBSUB", "NUMSUB", RedisAanmeldingen.KANAAL).await().atMost(WACHTTIJD).get(1).toLong()

    // Een vorige test kan nog aan het afmelden zijn; wacht tot de telling een poos gelijk blijft.
    private fun stabieleAbonnees(): Long {
        val einde = System.nanoTime() + WACHTTIJD.toNanos()
        var vorige = abonnees()
        var gelijk = 0

        while (gelijk < RUSTIGE_METINGEN) {
            // Een nulmeting die niet tot rust komt, zou een lek kunnen maskeren.
            if (System.nanoTime() > einde) fail("het aantal abonnees kwam niet tot rust")

            Thread.sleep(PEILING.toMillis())
            val nu = abonnees()
            gelijk = if (nu == vorige) gelijk + 1 else 0
            vorige = nu
        }

        return vorige
    }

    private fun pauzeer(duur: Duration) {
        redis.execute("CLIENT", "PAUSE", duur.toMillis().toString(), "WRITE").await().atMost(WACHTTIJD)
    }

    // Een mislukte test mag de gedeelde Redis niet gepauzeerd achterlaten voor de volgende.
    private fun hervat() {
        redis.execute("CLIENT", "UNPAUSE").await().atMost(WACHTTIJD)
    }

    private companion object {
        val WACHTTIJD: Duration = Duration.ofSeconds(5)
        const val HERHALINGEN = 2_000
        const val TABBLADEN = 5
        val STANDAARD_CONTROLE: Duration = Duration.ofSeconds(30)
        val SNELLE_CONTROLE: Duration = Duration.ofMillis(200)
        val AFWIKKELING: Duration = Duration.ofSeconds(2)

        val PEILING: Duration = Duration.ofMillis(100)
        const val RUSTIGE_METINGEN = 3

        val PROBE_TIMEOUT: Duration = RedisAanmeldingen.PROBE_TIMEOUT
        val TOT_OPGEVEN: Duration = PROBE_TIMEOUT.multipliedBy(RedisAanmeldingen.MAX_GEMISTE_PROBES.toLong())
        val TWEE_PROBES: Duration = TOT_OPGEVEN.plus(SNELLE_CONTROLE.multipliedBy(4))
        val TWEE_PROBES_ONDERGRENS: Duration = TOT_OPGEVEN.minusMillis(500)

        // Langer dan één probe, korter dan twee.
        val HAPERING: Duration = PROBE_TIMEOUT.plusSeconds(2)

        // Langer dan twee probes na elkaar, zodat het abonnement gegarandeerd opgegeven wordt.
        val PAUZE: Duration = TOT_OPGEVEN.plusSeconds(4)
    }
}
