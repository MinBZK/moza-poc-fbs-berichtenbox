package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenCache
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Het actief opruimen van ontvangers wier laatste sessie verlopen is, tegen een echte Redis, met de
 * inactiviteit van 2 s uit [KorteSessieRedisTestProfile]. De verloopindex is stelselbreed; elke test
 * begint daarom met een lege index, anders veegt hij ook wat eerdere tests achterlieten.
 */
@QuarkusTest
@TestProfile(KorteSessieRedisTestProfile::class)
class SessieVegenTest {

    @Inject
    lateinit var sessiecache: Sessiecache

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @BeforeEach
    fun legeVerloopindex() {
        redis.key().del(VERLOOP_INDEX).await().atMost(WACHTTIJD)
    }

    @Test
    fun `zonder verlopen sessies ruimt vegen niets op`() {
        val ontvanger = nieuweOntvanger()
        sessiecache.startSessie(ontvanger)
        zetGegevens(ontvanger)

        assertEquals(emptyList<Opruiming>(), sessiecache.ruimVerlopenSessiesOp())

        assertTrue(bestaat("${key(ontvanger)}:list"))
        assertTrue(ontvanger.toCanonicalString() in indexLeden())
    }

    @Test
    fun `verlopen laatste sessie wordt opgeruimd`() {
        val ontvanger = nieuweOntvanger()
        sessiecache.startSessie(ontvanger)
        zetGegevens(ontvanger)

        Thread.sleep(INACTIVITEIT_MS + 300)

        assertEquals(listOf(Opruiming(ontvanger, gelukt = true)), sessiecache.ruimVerlopenSessiesOp())

        assertEquals(emptyList<String>(), sleutelsOnder(key(ontvanger)))
        assertFalse(ontvanger.toCanonicalString() in indexLeden())
    }

    @Test
    fun `ontvanger met nog één lopende sessie blijft staan en krijgt de juiste score`() {
        val ontvanger = nieuweOntvanger()
        val k = key(ontvanger)
        sessiecache.startSessie(ontvanger)
        zetGegevens(ontvanger)
        // Een verlopen sessie naast de lopende, en een index die achterloopt op de lopende.
        redis.sortedSet(String::class.java).zadd("$k:sessies", 1.0, "verlopen").await().atMost(WACHTTIJD)
        redis.sortedSet(String::class.java).zadd(VERLOOP_INDEX, 1.0, ontvanger.toCanonicalString()).await().atMost(WACHTTIJD)

        assertEquals(emptyList<Opruiming>(), sessiecache.ruimVerlopenSessiesOp())

        val lopend = redis.sortedSet(String::class.java).zrangeWithScores("$k:sessies", 0, -1).await().atMost(WACHTTIJD)
        assertEquals(1, lopend.size, "De verlopen sessie hoort weg te zijn: $lopend")
        assertEquals(lopend.single().score(), indexScore(ontvanger))
        assertTrue(bestaat("$k:list"))
    }

    @Test
    fun `een ontvanger wiens gegevens al gewist zijn verdwijnt uit de index en wordt gemeld`() {
        // Zoals na een opruimen achter een schrijfactie, dat alleen de gegevens kent en niet de index.
        val ontvanger = nieuweOntvanger()
        zetVerlopen(ontvanger, metGegevens = false)

        assertEquals(listOf(Opruiming(ontvanger, gelukt = true)), sessiecache.ruimVerlopenSessiesOp())

        assertFalse(ontvanger.toCanonicalString() in indexLeden())
    }

    @Test
    fun `een BSN-ontvanger wordt uit de index teruggelezen`() {
        val bsn = Bsn("999993653")
        redis.key().del("${key(bsn)}:sessies", "${key(bsn)}:sessies:plafond").await().atMost(WACHTTIJD)
        zetVerlopen(bsn)

        assertEquals(listOf(Opruiming(bsn, gelukt = true)), sessiecache.ruimVerlopenSessiesOp())

        assertFalse(bestaat("${key(bsn)}:list"))
    }

    @Test
    fun `twee gelijktijdige vegers ruimen één keer op`() {
        val ontvangers = List(AANTAL_GELIJKTIJDIG) { nieuweOntvanger() }
        ontvangers.forEach { zetVerlopen(it) }
        val start = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)

        val rondes = try {
            List(2) {
                pool.submit<List<Opruiming>> {
                    start.await(WACHTTIJD_S, TimeUnit.SECONDS)
                    sessiecache.ruimVerlopenSessiesOp()
                }
            }.map { it.get(WACHTTIJD_S, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val gelukt = rondes.flatten().filter { it.gelukt }.map { it.ontvanger }
        assertEquals(ontvangers.size, gelukt.size, "Dubbel of niet opgeruimd: $rondes")
        assertEquals(ontvangers.toSet(), gelukt.toSet())
        assertTrue(rondes.flatten().all { it.gelukt })
        ontvangers.forEach { assertFalse(bestaat("${key(it)}:list")) }
    }

    @Test
    fun `max begrenst de batch`() {
        val ontvangers = List(3) { nieuweOntvanger() }
        ontvangers.forEach { zetVerlopen(it) }

        val eerste = sessiecache.ruimVerlopenSessiesOp(max = 2)

        assertEquals(2, eerste.size)
        assertEquals(1, indexLeden().size)

        val tweede = sessiecache.ruimVerlopenSessiesOp(max = 2)

        assertEquals(ontvangers.toSet(), (eerste + tweede).map { it.ontvanger }.toSet())
        assertEquals(emptyList<String>(), indexLeden())
    }

    @ParameterizedTest(name = "max {0}")
    @ValueSource(ints = [0, -1])
    fun `een max onder 1 is ongeldige invoer en veegt niets`(max: Int) {
        // -1: Redis leest een negatieve LIMIT als "alles".
        val ontvanger = nieuweOntvanger()
        zetVerlopen(ontvanger)

        assertThrows<SessiecacheException.OngeldigeInvoer> { sessiecache.ruimVerlopenSessiesOp(max) }

        assertTrue(bestaat("${key(ontvanger)}:list"))
    }

    @Test
    fun `een mislukt opruimen meldt gelukt false, laat de ontvanger in de index en veegt de rest`() {
        val stuk = nieuweOntvanger()
        val heel = nieuweOntvanger()
        zetVerlopen(heel)
        // Een sessiesleutel van het verkeerde type laat het script van deze ontvanger falen.
        redis.value(String::class.java).set("${key(stuk)}:sessies", "geen-zset").await().atMost(WACHTTIJD)
        redis.sortedSet(String::class.java).zadd(VERLOOP_INDEX, 1.0, stuk.toCanonicalString()).await().atMost(WACHTTIJD)

        val opruimingen = sessiecache.ruimVerlopenSessiesOp()

        assertEquals(setOf(Opruiming(stuk, gelukt = false), Opruiming(heel, gelukt = true)), opruimingen.toSet())
        assertEquals(listOf(stuk.toCanonicalString()), indexLeden())
        redis.key().del("${key(stuk)}:sessies").await().atMost(WACHTTIJD)
    }

    @Test
    fun `een onleesbaar lid wordt uit de index gehaald en niet gemeld`() {
        val ontvanger = nieuweOntvanger()
        zetVerlopen(ontvanger)
        redis.sortedSet(String::class.java).zadd(VERLOOP_INDEX, 1.0, "ONBEKEND:123").await().atMost(WACHTTIJD)

        assertEquals(listOf(Opruiming(ontvanger, gelukt = true)), sessiecache.ruimVerlopenSessiesOp())

        assertNull(redis.sortedSet(String::class.java).zscore(VERLOOP_INDEX, "ONBEKEND:123").await().atMost(WACHTTIJD))
    }

    @Test
    fun `een onbereikbare verloopindex is een Onbereikbaar`() {
        redis.value(String::class.java).set(VERLOOP_INDEX, "geen-zset").await().atMost(WACHTTIJD)

        assertThrows<SessiecacheException.Onbereikbaar> { sessiecache.ruimVerlopenSessiesOp() }
    }

    @Test
    fun `toString van een opruiming noemt het type maar niet de waarde`() {
        val bsn = Bsn("999993653")

        val tekst = Opruiming(bsn, gelukt = true).toString()

        assertFalse("999993653" in tekst, tekst)
        assertTrue("BSN" in tekst, tekst)
    }

    /** Een ontvanger wiens laatste sessie verliep zonder dat er geveegd is. */
    private fun zetVerlopen(ontvanger: Identificatienummer, metGegevens: Boolean = true) {
        val k = key(ontvanger)
        redis.sortedSet(String::class.java).zadd("$k:sessies", 1.0, "verlopen").await().atMost(WACHTTIJD)
        redis.hash(String::class.java).hset("$k:sessies:plafond", "verlopen", "1").await().atMost(WACHTTIJD)
        redis.sortedSet(String::class.java).zadd(VERLOOP_INDEX, 1.0, ontvanger.toCanonicalString()).await().atMost(WACHTTIJD)

        if (metGegevens) zetGegevens(ontvanger)
    }

    private fun zetGegevens(ontvanger: Identificatienummer) {
        redis.list(String::class.java).rpush("${key(ontvanger)}:list", "{}").await().atMost(WACHTTIJD)
    }

    private fun key(ontvanger: Identificatienummer) = BerichtenCache.cacheKey(ontvanger)

    private fun indexLeden(): List<String> =
        redis.sortedSet(String::class.java).zrange(VERLOOP_INDEX, 0, -1).await().atMost(WACHTTIJD)

    private fun indexScore(ontvanger: Identificatienummer): Double? =
        redis.sortedSet(String::class.java).zscore(VERLOOP_INDEX, ontvanger.toCanonicalString()).await().atMost(WACHTTIJD)

    private fun sleutelsOnder(prefix: String): List<String> =
        redis.execute("KEYS", "$prefix:*").await().atMost(WACHTTIJD).map { it.toString() }

    private fun bestaat(sleutel: String): Boolean = redis.key().exists(sleutel).await().atMost(WACHTTIJD)

    private companion object {
        const val VERLOOP_INDEX = "berichtensessiecache:v4:verloop"
        val WACHTTIJD: Duration = Duration.ofSeconds(5)
        const val WACHTTIJD_S = 10L
        const val INACTIVITEIT_MS = 2_000L
        const val AANTAL_GELIJKTIJDIG = 20
        private val teller = AtomicLong(System.nanoTime())

        fun nieuweOntvanger() = Oin(teller.incrementAndGet().toString().padStart(20, '0').takeLast(20))
    }
}
