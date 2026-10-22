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
import org.junit.jupiter.api.AfterEach
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
 * inactiviteit van 4 s uit [KorteSessieRedisTestProfile]. De verloopindex is stelselbreed; elke test
 * begint daarom met een lege index, anders veegt hij ook wat eerdere tests achterlieten, en ruimt
 * na afloop op wat hij bewust van het verkeerde type maakte: een volgende testklasse op dezelfde
 * Redis zou anders op een WRONGTYPE stuiten.
 */
@QuarkusTest
@TestProfile(KorteSessieRedisTestProfile::class)
class SessieVegenTest {

    @Inject
    lateinit var sessiecache: Sessiecache

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @Inject
    internal lateinit var register: SessieRegister

    private val beschadigd = mutableListOf<String>()

    @BeforeEach
    fun legeVerloopindex() {
        redis.key().del(VERLOOP_INDEX).await().atMost(WACHTTIJD)
    }

    @AfterEach
    fun herstelBeschadigdeSleutels() {
        redis.key().del(VERLOOP_INDEX, *beschadigd.toTypedArray()).await().atMost(WACHTTIJD)
    }

    @Test
    fun `zonder verlopen sessies ruimt vegen niets op`() {
        val ontvanger = nieuweOntvanger()
        sessiecache.startSessie(ontvanger)
        zetGegevens(ontvanger)

        assertEquals(emptyList<Opruiming>(), veeg())

        assertTrue(bestaat("${key(ontvanger)}:list"))
        assertTrue(lid(ontvanger) in indexLeden())
    }

    @Test
    fun `verlopen laatste sessie wordt opgeruimd`() {
        val ontvanger = nieuweOntvanger()
        sessiecache.startSessie(ontvanger)
        zetGegevens(ontvanger)

        Thread.sleep(INACTIVITEIT_MS + 300)

        assertEquals(listOf(Opruiming.VanOntvanger(ontvanger, OpruimUitkomst.GEWIST)), veeg())

        assertEquals(emptyList<String>(), sleutelsOnder(key(ontvanger)))
        assertFalse(lid(ontvanger) in indexLeden())
    }

    @Test
    fun `ontvanger met nog één lopende sessie blijft staan en krijgt de juiste score`() {
        val ontvanger = nieuweOntvanger()
        val k = key(ontvanger)
        sessiecache.startSessie(ontvanger)
        zetGegevens(ontvanger)
        // Een verlopen sessie naast de lopende, en een index die achterloopt op de lopende.
        redis.sortedSet(String::class.java).zadd("$k:sessies", 1.0, "verlopen").await().atMost(WACHTTIJD)
        redis.sortedSet(String::class.java).zadd(VERLOOP_INDEX, 1.0, lid(ontvanger)).await().atMost(WACHTTIJD)

        assertEquals(emptyList<Opruiming>(), veeg())

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

        assertEquals(listOf(Opruiming.VanOntvanger(ontvanger, OpruimUitkomst.GEWIST)), veeg())

        assertFalse(lid(ontvanger) in indexLeden())
    }

    @Test
    fun `een BSN-ontvanger wordt uit de index teruggelezen`() {
        val bsn = Bsn("999993653")
        redis.key().del("${key(bsn)}:sessies", "${key(bsn)}:sessies:plafond").await().atMost(WACHTTIJD)
        zetVerlopen(bsn)

        assertEquals(listOf(Opruiming.VanOntvanger(bsn, OpruimUitkomst.GEWIST)), veeg())

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
                    veeg()
                }
            }.map { it.get(WACHTTIJD_S, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val gewist = rondes.flatten().filter { it.uitkomst == OpruimUitkomst.GEWIST }.map { (it as Opruiming.VanOntvanger).ontvanger }
        assertEquals(ontvangers.size, gewist.size, "Dubbel of niet opgeruimd: $rondes")
        assertEquals(ontvangers.toSet(), gewist.toSet())
        assertTrue(rondes.flatten().all { it.uitkomst == OpruimUitkomst.GEWIST })
        ontvangers.forEach { assertFalse(bestaat("${key(it)}:list")) }
    }

    @Test
    fun `max begrenst de batch`() {
        val ontvangers = List(3) { nieuweOntvanger() }
        ontvangers.forEach { zetVerlopen(it) }

        val eerste = veeg(max = 2)

        assertEquals(2, eerste.size)
        assertEquals(1, indexLeden().size)

        val tweede = veeg(max = 2)

        assertEquals(ontvangers.toSet(), (eerste + tweede).map { (it as Opruiming.VanOntvanger).ontvanger }.toSet())
        assertEquals(emptyList<String>(), indexLeden())
    }

    @ParameterizedTest(name = "max {0}")
    @ValueSource(ints = [0, -1])
    fun `een max onder 1 is ongeldige invoer en veegt niets`(max: Int) {
        // -1: Redis leest een negatieve LIMIT als "alles".
        val ontvanger = nieuweOntvanger()
        zetVerlopen(ontvanger)

        assertThrows<SessiecacheException.OngeldigeInvoer> { veeg(max) }

        assertTrue(bestaat("${key(ontvanger)}:list"))
    }

    @Test
    fun `een mislukt opruimen meldt MISLUKT, laat de ontvanger in de index en veegt de rest`() {
        val stuk = nieuweOntvanger()
        val heel = nieuweOntvanger()
        zetVerlopen(heel)
        // Een sessiesleutel van het verkeerde type laat het script van deze ontvanger falen.
        beschadigd += "${key(stuk)}:sessies"
        redis.value(String::class.java).set("${key(stuk)}:sessies", "geen-zset").await().atMost(WACHTTIJD)
        redis.sortedSet(String::class.java).zadd(VERLOOP_INDEX, 1.0, lid(stuk)).await().atMost(WACHTTIJD)
        redis.value(String::class.java).set("${key(stuk)}:ontvanger", stuk.toCanonicalString()).await().atMost(WACHTTIJD)

        val opruimingen = veeg()

        assertEquals(setOf(Opruiming.VanOntvanger(stuk, OpruimUitkomst.MISLUKT), Opruiming.VanOntvanger(heel, OpruimUitkomst.GEWIST)), opruimingen.toSet())
        assertEquals(listOf(lid(stuk)), indexLeden())
    }

    @Test
    fun `honderd mislukkers schuiven naar achteren en houden een gezonde ontvanger niet tegen`() {
        val stuk = List(AANTAL_MISLUKT) { nieuweOntvanger() }
        stuk.forEach(::zetBeschadigd)
        // Achter alle mislukkers in de index: zonder uitstel komt hij nooit in een batch van honderd.
        val heel = nieuweOntvanger()
        zetVerlopen(heel)
        redis.sortedSet(String::class.java).zadd(VERLOOP_INDEX, 2.0, lid(heel)).await().atMost(WACHTTIJD)

        val eerste = veeg(max = AANTAL_MISLUKT)

        assertEquals(stuk.map { Opruiming.VanOntvanger(it, OpruimUitkomst.MISLUKT) }.toSet(), eerste.toSet())
        val ondergrens = redisTijdMs() + UITSTEL_MS - MARGE_MS
        stuk.forEach { assertTrue(indexScore(it)!! >= ondergrens, "Niet naar achteren geschoven: ${indexScore(it)}") }

        assertEquals(listOf(Opruiming.VanOntvanger(heel, OpruimUitkomst.GEWIST)), veeg(max = AANTAL_MISLUKT))
        assertFalse(bestaat("${key(heel)}:list"))
        assertEquals(emptyList<Opruiming>(), veeg(max = AANTAL_MISLUKT))
        assertEquals(AANTAL_MISLUKT, indexLeden().size, "De mislukkers horen in de index te blijven")
    }

    @Test
    fun `uitstellen zet geen ontvanger terug die uit de index is en haalt geen later verloop naar voren`() {
        val weg = nieuweOntvanger()
        val later = nieuweOntvanger()
        val laterScore = redisTijdMs() + Duration.ofDays(1).toMillis().toDouble()
        redis.sortedSet(String::class.java).zadd(VERLOOP_INDEX, laterScore, lid(later)).await().atMost(WACHTTIJD)

        register.stelUit(lid(weg)).await().atMost(WACHTTIJD)
        register.stelUit(lid(later)).await().atMost(WACHTTIJD)

        assertNull(indexScore(weg))
        assertEquals(laterScore, indexScore(later))
    }

    @ParameterizedTest(name = "leesbare ontvanger {0}")
    @ValueSource(strings = ["verlopen", "onleesbaar", "van het verkeerde type"])
    fun `een kandidaat zonder leesbare ontvanger wordt toch geveegd en zonder ontvanger gemeld`(geval: String) {
        // Zoals na een veeg die zo lang faalde dat de vangnet-TTL van de leesbare ontvanger verliep.
        val ontvanger = nieuweOntvanger()
        val ontvangerKey = "${key(ontvanger)}:ontvanger"
        zetVerlopen(ontvanger)

        when (geval) {
            "verlopen" -> redis.key().del(ontvangerKey).await().atMost(WACHTTIJD)
            "onleesbaar" -> redis.value(String::class.java).set(ontvangerKey, "ONBEKEND:123").await().atMost(WACHTTIJD)
            else -> {
                beschadigd += ontvangerKey
                redis.key().del(ontvangerKey).await().atMost(WACHTTIJD)
                redis.list(String::class.java).rpush(ontvangerKey, "geen-string").await().atMost(WACHTTIJD)
            }
        }

        assertEquals(listOf(Opruiming.ZonderOntvanger(lid(ontvanger), OpruimUitkomst.GEWIST)), veeg())

        assertEquals(emptyList<String>(), indexLeden())
        assertFalse(bestaat("${key(ontvanger)}:list"))
    }

    @Test
    fun `de index noemt alleen kenmerken, en de leesbare ontvanger staat apart met de vangnet-TTL`() {
        val bsn = Bsn("999993653")
        redis.key().del("${key(bsn)}:sessies", "${key(bsn)}:sessies:plafond").await().atMost(WACHTTIJD)

        sessiecache.startSessie(bsn)

        assertEquals(listOf(lid(bsn)), indexLeden())
        assertTrue(indexLeden().none { bsn.waarde in it }, "BSN leesbaar in de verloopindex")
        assertEquals(bsn.toCanonicalString(), redis.value(String::class.java).get("${key(bsn)}:ontvanger").await().atMost(WACHTTIJD))
        assertTrue(pttl("${key(bsn)}:ontvanger") in (MAX_DUUR_MS + 1)..DATA_TTL_MS, "TTL: ${pttl("${key(bsn)}:ontvanger")}")

        sessiecache.beeindigSessie(sessiecache.startSessie(bsn).sessieId, bsn)

        assertTrue(bestaat("${key(bsn)}:ontvanger"), "met een lopende sessie blijft de leesbare ontvanger staan")
    }

    @Test
    fun `een onbereikbare verloopindex is een Onbereikbaar`() {
        redis.value(String::class.java).set(VERLOOP_INDEX, "geen-zset").await().atMost(WACHTTIJD)

        assertThrows<SessiecacheException.Onbereikbaar> { veeg() }
    }

    @Test
    fun `opruimen na schrijven zonder sessie laat de ontvanger wachtend in de index, en de veeg meldt hem`() {
        val ontvanger = nieuweOntvanger()
        zetGegevens(ontvanger)

        assertTrue(register.ruimOpZonderSessie(ontvanger).await().atMost(WACHTTIJD))

        assertTrue(indexScore(ontvanger)!! <= redisTijdMs(), "niet wachtend: ${indexScore(ontvanger)}")
        assertTrue(register.veeg(lid(ontvanger)).await().atMost(WACHTTIJD))
    }

    @Test
    fun `opruimen na schrijven naast een lopende sessie laat de index ongemoeid`() {
        val ontvanger = nieuweOntvanger()
        sessiecache.startSessie(ontvanger)
        zetGegevens(ontvanger)
        val score = indexScore(ontvanger)

        assertFalse(register.ruimOpZonderSessie(ontvanger).await().atMost(WACHTTIJD))

        assertEquals(score, indexScore(ontvanger))
    }

    @Test
    fun `opruimen na schrijven zonder sessie en zonder gegevens zet niemand in de index`() {
        val ontvanger = nieuweOntvanger()

        assertFalse(register.ruimOpZonderSessie(ontvanger).await().atMost(WACHTTIJD))

        assertNull(indexScore(ontvanger))
    }

    @Test
    fun `uitloggen na een opruimen achter een verlopen sessie meldt het wissen, en de veeg daarna niet meer`() {
        // Laatste sessie verlopen, dan een schrijfactie die zelf opruimt, dan pas het uitloggen: zonder
        // de wachtende index-entry wist het uitloggen niets en haalt hij de taak van de veegtaak weg.
        val ontvanger = nieuweOntvanger()
        val sessie = sessiecache.startSessie(ontvanger)
        Thread.sleep(INACTIVITEIT_MS + 300)
        zetGegevens(ontvanger)
        assertTrue(register.ruimOpZonderSessie(ontvanger).await().atMost(WACHTTIJD))

        assertEquals(SessieEinde.OPGERUIMD, sessiecache.beeindigSessie(sessie.sessieId, ontvanger))

        assertFalse(register.veeg(lid(ontvanger)).await().atMost(WACHTTIJD))
        assertNull(indexScore(ontvanger))
    }

    @Test
    fun `een start na een opruimen achter een verlopen sessie meldt het wissen als resten`() {
        val ontvanger = nieuweOntvanger()
        sessiecache.startSessie(ontvanger)
        Thread.sleep(INACTIVITEIT_MS + 300)
        zetGegevens(ontvanger)
        assertTrue(register.ruimOpZonderSessie(ontvanger).await().atMost(WACHTTIJD))

        assertTrue(sessiecache.startSessie(ontvanger).restenOpgeruimd)

        assertFalse(register.veeg(lid(ontvanger)).await().atMost(WACHTTIJD))
    }

    @Test
    fun `een start zonder wachtende taak en zonder gegevens meldt geen resten`() {
        val ontvanger = nieuweOntvanger()

        assertFalse(sessiecache.startSessie(ontvanger).restenOpgeruimd)
    }

    @Test
    fun `markeren voor de veeg zet een ontvanger zonder entry wachtend in de index`() {
        val ontvanger = nieuweOntvanger()

        register.markeerVoorVeeg(ontvanger).await().atMost(WACHTTIJD)

        assertTrue(indexScore(ontvanger)!! <= redisTijdMs(), "niet wachtend: ${indexScore(ontvanger)}")
    }

    @Test
    fun `markeren voor de veeg laat het verloop van een lopende sessie staan`() {
        val ontvanger = nieuweOntvanger()
        sessiecache.startSessie(ontvanger)
        val score = indexScore(ontvanger)!!

        register.markeerVoorVeeg(ontvanger).await().atMost(WACHTTIJD)

        assertEquals(score, indexScore(ontvanger))
        assertTrue(score > redisTijdMs())
    }

    @Test
    fun `toString van een opruiming noemt het type maar niet de waarde`() {
        val bsn = Bsn("999993653")

        val tekst = Opruiming.VanOntvanger(bsn, OpruimUitkomst.GEWIST).toString()

        assertFalse("999993653" in tekst, tekst)
        assertTrue("BSN" in tekst, tekst)

        val zonder = Opruiming.ZonderOntvanger(lid(bsn), OpruimUitkomst.GEWIST).toString()
        val kandidaat = VeegKandidaat(lid(bsn), bsn).toString()

        listOf(zonder, kandidaat).forEach { assertFalse(lid(bsn) in it || "999993653" in it, it) }
    }

    private fun veeg(max: Int = 100): List<Opruiming> {
        val gemeld = mutableListOf<Opruiming>()
        sessiecache.ruimVerlopenSessiesOp(max) { gemeld += it }

        return gemeld
    }

    /** Een verlopen ontvanger wiens sessiesleutel van het verkeerde type is, zodat zijn script faalt. */
    private fun zetBeschadigd(ontvanger: Identificatienummer) {
        beschadigd += "${key(ontvanger)}:sessies"
        redis.value(String::class.java).set("${key(ontvanger)}:sessies", "geen-zset").await().atMost(WACHTTIJD)
        redis.sortedSet(String::class.java).zadd(VERLOOP_INDEX, 1.0, lid(ontvanger)).await().atMost(WACHTTIJD)
        redis.value(String::class.java).set("${key(ontvanger)}:ontvanger", ontvanger.toCanonicalString()).await().atMost(WACHTTIJD)
    }

    private fun redisTijdMs(): Double {
        val tijd = redis.execute("TIME").await().atMost(WACHTTIJD)

        return tijd.get(0).toLong() * 1000.0 + tijd.get(1).toLong() / 1000
    }

    /** Een ontvanger wiens laatste sessie verliep zonder dat er geveegd is. */
    private fun zetVerlopen(ontvanger: Identificatienummer, metGegevens: Boolean = true) {
        val k = key(ontvanger)
        redis.sortedSet(String::class.java).zadd("$k:sessies", 1.0, "verlopen").await().atMost(WACHTTIJD)
        redis.hash(String::class.java).hset("$k:sessies:plafond", "verlopen", "1").await().atMost(WACHTTIJD)
        redis.sortedSet(String::class.java).zadd(VERLOOP_INDEX, 1.0, lid(ontvanger)).await().atMost(WACHTTIJD)
        redis.value(String::class.java).set("$k:ontvanger", ontvanger.toCanonicalString()).await().atMost(WACHTTIJD)

        if (metGegevens) zetGegevens(ontvanger)
    }

    private fun zetGegevens(ontvanger: Identificatienummer) {
        redis.list(String::class.java).rpush("${key(ontvanger)}:list", "{}").await().atMost(WACHTTIJD)
    }

    private fun key(ontvanger: Identificatienummer) = BerichtenCache.cacheKey(ontvanger)

    private fun lid(ontvanger: Identificatienummer) = BerichtenCache.kenmerk(ontvanger)

    private fun pttl(sleutel: String): Long = redis.key().pttl(sleutel).await().atMost(WACHTTIJD)

    private fun indexLeden(): List<String> =
        redis.sortedSet(String::class.java).zrange(VERLOOP_INDEX, 0, -1).await().atMost(WACHTTIJD)

    private fun indexScore(ontvanger: Identificatienummer): Double? =
        redis.sortedSet(String::class.java).zscore(VERLOOP_INDEX, lid(ontvanger)).await().atMost(WACHTTIJD)

    private fun sleutelsOnder(prefix: String): List<String> =
        redis.execute("KEYS", "$prefix:*").await().atMost(WACHTTIJD).map { it.toString() }

    private fun bestaat(sleutel: String): Boolean = redis.key().exists(sleutel).await().atMost(WACHTTIJD)

    private companion object {
        const val VERLOOP_INDEX = "berichtensessiecache:v4:verloop"
        val WACHTTIJD: Duration = Duration.ofSeconds(5)
        const val WACHTTIJD_S = 10L
        const val INACTIVITEIT_MS = 4_000L
        const val MAX_DUUR_MS = 6_000L

        // Maximale duur plus de standaard vangnet-marge van een uur.
        const val DATA_TTL_MS = MAX_DUUR_MS + 3_600_000L
        const val AANTAL_GELIJKTIJDIG = 20
        const val AANTAL_MISLUKT = 100

        // Het veeg-uitstel bij het standaardinterval van 30 s; de marge dekt de duur van de test zelf.
        const val UITSTEL_MS = 30_000.0
        const val MARGE_MS = 5_000.0
        private val teller = AtomicLong(System.nanoTime())

        fun nieuweOntvanger() = Oin(teller.incrementAndGet().toString().padStart(20, '0').takeLast(20))
    }
}
