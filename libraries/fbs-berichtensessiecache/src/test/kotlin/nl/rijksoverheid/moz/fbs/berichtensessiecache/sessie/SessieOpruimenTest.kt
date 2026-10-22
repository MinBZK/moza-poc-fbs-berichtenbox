package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.ws.rs.WebApplicationException
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AggregationStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenCache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockMagazijnClientFactory
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockMagazijnResolver
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenGereed
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.sleutel
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.WireMockMagazijnResource
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Het wissen van de berichtgegevens aan het einde van de laatste sessie, tegen een echte Redis:
 * wat er weg moet (ook hashes van eerdere rondes, aanmeldingen en tombstones), wat er gebeurt als
 * schrijven en uitloggen elkaar kruisen, en de TTL op de gegevens als vangnet. Duren uit
 * [OpruimRedisTestProfile]: maximale duur 20 s, marge 1 s.
 */
@QuarkusTest
@TestProfile(OpruimRedisTestProfile::class)
class SessieOpruimenTest {

    @Inject
    lateinit var sessiecache: Sessiecache

    @Inject
    internal lateinit var berichtenCache: BerichtenCache

    @Inject
    internal lateinit var sessies: SessieRegister

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    // Uniek per test: de Redis-container wordt gedeeld tussen de tests van dit profiel.
    private val ontvanger = nieuweOntvanger()
    private val k = BerichtenCache.cacheKey(ontvanger)

    @AfterEach
    fun laatMagazijnenLos() {
        MockMagazijnClientFactory.tegenhouden?.countDown()
        MockMagazijnClientFactory.tegenhouden = null
        MockMagazijnClientFactory.bevraagd = null
        MockMagazijnResolver.tegenhouden?.countDown()
        MockMagazijnResolver.tegenhouden = null
        MockMagazijnResolver.bereikt = null
        MockMagazijnResolver.fout = null
    }

    @Test
    fun `een mislukte ronde die kruist met het einde van de laatste sessie laat geen status achter`() {
        // De FOUT-status volgt op het opruimen; de verloopindex kent de ontvanger dan al niet meer,
        // dus zonder opruimen na die schrijfactie blijft hij tot de vangnet-TTL staan.
        val sessie = sessiecache.startSessie(ontvanger)
        vulAlsOpgehaald(listOf(bericht()))
        val bereikt = CountDownLatch(1)
        MockMagazijnResolver.bereikt = bereikt
        MockMagazijnResolver.tegenhouden = CountDownLatch(1)
        MockMagazijnResolver.fout = IllegalStateException("resolver stuk")

        val ronde = CompletableFuture.supplyAsync { runCatching { sessiecache.ophalen(ontvanger) } }

        assertTrue(bereikt.await(WACHTTIJD_S, TimeUnit.SECONDS), "de ronde kwam niet bij de resolver")
        assertEquals(SessieEinde.OPGERUIMD, sessiecache.beeindigSessie(sessie.sessieId, ontvanger))
        MockMagazijnResolver.tegenhouden?.countDown()

        assertTrue(ronde.get(RONDE_S, TimeUnit.SECONDS).isFailure, "de ronde hoort te falen")
        assertNietsAchter(emptyList())
    }

    @Test
    fun `verwijderen dat kruist met het einde van de laatste sessie laat geen tombstone achter`() {
        // Zonder lopende sessie op het moment van schrijven: zoals een delete die de hash nog las
        // vóór het opruimen en zijn tombstone erna schrijft.
        val berichten = listOf(bericht())
        vulAlsOpgehaald(berichten)

        sessiecache.verwijder(ontvanger, berichten[0].magazijnId, berichten[0].berichtId)

        assertFalse(bestaat(BerichtenCache.verwijderdKey(ontvanger, berichten[0].sleutel)))
        assertNietsAchter(berichten, wachtend = true)
    }

    @Test
    fun `verwijderen naast een lopende sessie laat de tombstone staan`() {
        sessiecache.startSessie(ontvanger)
        val berichten = listOf(bericht())
        vulAlsOpgehaald(berichten)

        sessiecache.verwijder(ontvanger, berichten[0].magazijnId, berichten[0].berichtId)

        assertTrue(bestaat(BerichtenCache.verwijderdKey(ontvanger, berichten[0].sleutel)))
    }

    @Test
    fun `opruimen wist een sleutelset die groter is dan één stuk`() {
        // 1001: het tweede stuk heeft precies één lid, de grens waar een rekenfout in de stukken zit.
        val sleutels = (1..AANTAL_OVER_GRENS).map { "$k:verwijderd:grens:$it" }
        redis.value(String::class.java).mset(sleutels.associateWith { "1" }).await().atMost(WACHTTIJD)
        redis.set(String::class.java).sadd("$k:sleutels", *sleutels.toTypedArray()).await().atMost(WACHTTIJD)

        assertTrue(sessies.ruimOpZonderSessie(ontvanger).await().atMost(WACHTTIJD))

        // Alleen de leesbare ontvanger, bij de wachtende index-entry voor de veegtaak.
        assertEquals(listOf("$k:ontvanger"), sleutelsOnder(k))
    }

    @ParameterizedTest(name = "{0} berichten")
    @ValueSource(ints = [0, 1, 3])
    fun `uitloggen van de laatste sessie laat niets van de ontvanger achter`(aantal: Int) {
        val sessie = sessiecache.startSessie(ontvanger)
        val berichten = List(aantal) { bericht() }
        vulAlsOpgehaald(berichten)

        assertEquals(SessieEinde.OPGERUIMD, sessiecache.beeindigSessie(sessie.sessieId, ontvanger))

        assertNietsAchter(berichten)
    }

    @Test
    fun `hashes uit een eerdere ophaalronde, aanmeldingen en tombstones worden ook opgeruimd`() {
        val sessie = sessiecache.startSessie(ontvanger)
        val eerste = List(2) { bericht() }
        val tweede = listOf(bericht())
        val aangemeld = bericht()
        vulAlsOpgehaald(eerste)
        vulAlsOpgehaald(tweede)
        berichtenCache.createBericht(aangemeld, ontvanger).await().atMost(WACHTTIJD)
        berichtenCache.delete(tweede[0].sleutel, ontvanger).await().atMost(WACHTTIJD)

        // De tweede ronde vervangt de lijst, niet de hashes van de eerste: die staan er nog.
        assertTrue(bestaat(BerichtenCache.berichtKey(eerste[0].sleutel)))
        assertTrue(bestaat(BerichtenCache.verwijderdKey(ontvanger, tweede[0].sleutel)))

        assertEquals(SessieEinde.OPGERUIMD, sessiecache.beeindigSessie(sessie.sessieId, ontvanger))

        assertNietsAchter(eerste + tweede + aangemeld)
    }

    @Test
    fun `uitloggen naast een andere sessie laat de gegevens staan`() {
        val eerste = sessiecache.startSessie(ontvanger)
        sessiecache.startSessie(ontvanger)
        val berichten = listOf(bericht())
        vulAlsOpgehaald(berichten)

        assertEquals(SessieEinde.BEEINDIGD, sessiecache.beeindigSessie(eerste.sessieId, ontvanger))

        assertTrue(bestaat(BerichtenCache.berichtKey(berichten[0].sleutel)))
        assertNotNull(berichtenCache.getAggregationStatus(k).await().atMost(WACHTTIJD))
    }

    @Test
    fun `einde van de laatste sessie tijdens het ophalen laat na afronding niets achter`() {
        // De magazijn-stub levert altijd berichten aan deze BSN; de ronde schrijft dus zijn hashes.
        val bsn = Bsn("999993653")
        val bsnKey = BerichtenCache.cacheKey(bsn)
        val stubBerichten = MockMagazijnClientFactory.testBerichtenA + MockMagazijnClientFactory.testBerichtenB
        redis.key().del(*stubBerichten.map { BerichtenCache.berichtKey(it.sleutel) }.toTypedArray()).await().atMost(WACHTTIJD)
        sessies.ruimOpZonderSessie(bsn).await().atMost(WACHTTIJD)

        val sessie = sessiecache.startSessie(bsn)
        val bevraagd = CountDownLatch(1)
        MockMagazijnClientFactory.bevraagd = bevraagd
        MockMagazijnClientFactory.tegenhouden = CountDownLatch(1)

        val ronde = sessiecache.ophalen(bsn).collect().asList().subscribeAsCompletionStage()

        assertTrue(bevraagd.await(WACHTTIJD_S, TimeUnit.SECONDS), "de ronde kwam niet bij de magazijnen")
        assertEquals(SessieEinde.OPGERUIMD, sessiecache.beeindigSessie(sessie.sessieId, bsn))
        MockMagazijnClientFactory.tegenhouden?.countDown()

        val events = ronde.toCompletableFuture().get(RONDE_S, TimeUnit.SECONDS)

        assertTrue(events.none { it is OphalenGereed }, "Geen GEREED na uitloggen: $events")
        assertEquals(emptyList<String>(), sleutelsOnder(bsnKey))
        stubBerichten.forEach { assertFalse(bestaat(BerichtenCache.berichtKey(it.sleutel)), "${it.sleutel} achtergebleven") }
        assertEquals(0L, zoekTotaal(bsn))
    }

    @Test
    fun `een ronde die een uitlog overleeft schrijft niets in de nieuwe sessie en laat de lock van de volgende ronde staan`() {
        // Uitloggen en direct weer inloggen terwijl ronde 1 nog loopt: het opruimen wist de lock, dus
        // een volgende ronde kan hem nemen. Die volgende ronde staat hier als lock met een eigen token:
        // een tweede echte ronde zou op dezelfde magazijn-stub achter ronde 1 aan wachten.
        val bsn = Bsn("999993653")
        val bsnKey = BerichtenCache.cacheKey(bsn)
        val stubBerichten = MockMagazijnClientFactory.testBerichtenA + MockMagazijnClientFactory.testBerichtenB
        redis.key().del(*stubBerichten.map { BerichtenCache.berichtKey(it.sleutel) }.toTypedArray()).await().atMost(WACHTTIJD)
        sessies.ruimOpZonderSessie(bsn).await().atMost(WACHTTIJD)
        val eersteSessie = sessiecache.startSessie(bsn)
        val bevraagd = CountDownLatch(1)
        MockMagazijnClientFactory.bevraagd = bevraagd
        MockMagazijnClientFactory.tegenhouden = CountDownLatch(1)

        val eersteRonde = sessiecache.ophalen(bsn).collect().asList().subscribeAsCompletionStage()

        assertTrue(bevraagd.await(WACHTTIJD_S, TimeUnit.SECONDS), "ronde 1 kwam niet bij de magazijnen")
        assertEquals(SessieEinde.OPGERUIMD, sessiecache.beeindigSessie(eersteSessie.sessieId, bsn))
        val tweedeSessie = sessiecache.startSessie(bsn)

        try {
            val bezig = AggregationStatus(status = OphalenStatus.BEZIG, totaalMagazijnen = 2)
            assertTrue(berichtenCache.trySetAggregationStatus(bsnKey, bezig, "ronde-2").await().atMost(WACHTTIJD))

            MockMagazijnClientFactory.tegenhouden?.countDown()
            val events = eersteRonde.toCompletableFuture().get(RONDE_S, TimeUnit.SECONDS)

            assertTrue(events.none { it is OphalenGereed }, "Ronde 1 hoort niet af te ronden: $events")
            assertEquals(bezig, berichtenCache.getAggregationStatus(bsnKey).await().atMost(WACHTTIJD))
            assertEquals("ronde-2", redis.value(String::class.java).get("$bsnKey:lock").await().atMost(WACHTTIJD))
            assertFalse(bestaat("$bsnKey:list"), "ronde 1 schreef haar lijst in de nieuwe sessie")
            stubBerichten.forEach { assertFalse(bestaat(BerichtenCache.berichtKey(it.sleutel)), "${it.sleutel} van ronde 1 staat er") }
            assertThrows<WebApplicationException>("de lock van ronde 2 hoort er nog te staan") { sessiecache.ophalen(bsn) }
        } finally {
            sessiecache.beeindigSessie(tweedeSessie.sessieId, bsn)
        }
    }

    @Test
    fun `aanmelding na uitloggen schrijft niets`() {
        val sessie = sessiecache.startSessie(ontvanger)
        vulAlsOpgehaald(listOf(bericht()))
        sessiecache.beeindigSessie(sessie.sessieId, ontvanger)
        val nieuw = bericht()

        assertThrows<SessiecacheException.GeenLijstInCache> { sessiecache.schrijfBericht(ontvanger, nieuw) }

        assertNietsAchter(listOf(nieuw))
    }

    @Test
    fun `aanmelding die kruist met uitloggen laat niets achter`() {
        // De volgorde die de statuscontrole van de aanmelding niet ziet: status nog aanwezig, dan
        // het einde van de laatste sessie, dan pas het schrijven.
        val sessie = sessiecache.startSessie(ontvanger)
        vulAlsOpgehaald(listOf(bericht()))
        assertNotNull(berichtenCache.getAggregationStatus(k).await().atMost(WACHTTIJD))
        sessiecache.beeindigSessie(sessie.sessieId, ontvanger)
        val nieuw = bericht()

        berichtenCache.createBericht(nieuw, ontvanger).await().atMost(WACHTTIJD)

        assertTrue(sessies.ruimOpZonderSessie(ontvanger).await().atMost(WACHTTIJD))
        assertNietsAchter(listOf(nieuw), wachtend = true)
    }

    @Test
    fun `aanmelding naast een lopende sessie blijft staan`() {
        sessiecache.startSessie(ontvanger)
        vulAlsOpgehaald(emptyList())
        val nieuw = bericht()

        sessiecache.schrijfBericht(ontvanger, nieuw)

        assertTrue(bestaat(BerichtenCache.berichtKey(nieuw.sleutel)))
    }

    @Test
    fun `opruimen zonder sessie en zonder gegevens meldt niets`() {
        assertFalse(sessies.ruimOpZonderSessie(ontvanger).await().atMost(WACHTTIJD))
    }

    @Test
    fun `opruimen zonder sessie ruimt op als alleen een verlopen sessie over is`() {
        vulAlsOpgehaald(listOf(bericht()))
        // Een lid met een verloop in het verleden: zoals een sessie die verliep zonder dat er geveegd is.
        redis.sortedSet(String::class.java).zadd("$k:sessies", 1.0, "verlopen").await().atMost(WACHTTIJD)

        assertTrue(sessies.ruimOpZonderSessie(ontvanger).await().atMost(WACHTTIJD))
        assertNietsAchter(emptyList(), wachtend = true)
    }

    @Test
    fun `start zonder lopende sessie ruimt resten op en meldt restenOpgeruimd`() {
        val resten = List(2) { bericht() }
        vulAlsOpgehaald(resten)
        berichtenCache.delete(resten[0].sleutel, ontvanger).await().atMost(WACHTTIJD)

        val start = sessiecache.startSessie(ontvanger)

        assertTrue(start.restenOpgeruimd)
        assertEquals(listOf("$k:ontvanger", "$k:sessies", "$k:sessies:plafond"), sleutelsOnder(k).sorted())
        resten.forEach { assertFalse(bestaat(BerichtenCache.berichtKey(it.sleutel))) }
    }

    @Test
    fun `start naast een lopende sessie trekt de TTL op tot voorbij het plafond van de nieuwe sessie`() {
        sessiecache.startSessie(ontvanger)
        val berichten = List(2) { bericht() }
        vulAlsOpgehaald(berichten)
        berichtenCache.delete(berichten[0].sleutel, ontvanger).await().atMost(WACHTTIJD)
        val gegevens = listOf(
            "$k:list",
            "$k:status",
            "$k:sleutels",
            BerichtenCache.berichtKey(berichten[1].sleutel),
            BerichtenCache.verwijderdKey(ontvanger, berichten[0].sleutel),
        )

        Thread.sleep(1_500)

        // Zonder optrekken verlopen deze vóór het plafond van een sessie die nu start.
        gegevens.forEach { assertTrue(pttl(it) in 1 until MAX_DUUR_MS, "$it: ${pttl(it)} ms") }

        sessiecache.startSessie(ontvanger)

        gegevens.forEach { assertTrue(pttl(it) > MAX_DUUR_MS, "$it niet opgetrokken: ${pttl(it)} ms") }
    }

    @Test
    fun `een BEZIG-status houdt bij een tweede start zijn korte TTL`() {
        sessiecache.startSessie(ontvanger)
        berichtenCache.trySetAggregationStatus(k, AggregationStatus(status = OphalenStatus.BEZIG), "ronde").await().atMost(WACHTTIJD)

        sessiecache.startSessie(ontvanger)

        assertTrue(pttl("$k:status") in 1..LOCK_TTL_MS, "BEZIG-status opgetrokken: ${pttl("$k:status")} ms")
    }

    @Test
    fun `de TTL na het schrijven is maximale duur plus marge, en lezen verlengt hem niet`() {
        val berichten = listOf(bericht())
        vulAlsOpgehaald(berichten)
        val gegevens = listOf("$k:list", "$k:status", "$k:sleutels", BerichtenCache.berichtKey(berichten[0].sleutel))

        gegevens.forEach { assertTrue(pttl(it) in (MAX_DUUR_MS + 1)..DATA_TTL_MS, "$it: ${pttl(it)} ms") }

        Thread.sleep(1_200)
        berichtenCache.getPage(k, 0, 20).await().atMost(WACHTTIJD)
        berichtenCache.getById(berichten[0].sleutel, ontvanger).await().atMost(WACHTTIJD)

        gegevens.forEach { assertTrue(pttl(it) < MAX_DUUR_MS, "$it verlengd door lezen: ${pttl(it)} ms") }
    }

    private fun vulAlsOpgehaald(berichten: List<Bericht>) {
        berichtenCache.store(k, berichten).await().atMost(WACHTTIJD)
        berichtenCache.storeAggregationStatus(
            k,
            AggregationStatus(status = OphalenStatus.GEREED, totaalMagazijnen = 1, geslaagd = 1),
        ).await().atMost(WACHTTIJD)
    }

    /**
     * [wachtend]: het wissen gebeurde zonder eigen logregel, dus de ontvanger wacht in de verloopindex
     * op de veegtaak, en zijn leesbare vorm blijft daarvoor staan tot die veeg.
     */
    private fun assertNietsAchter(berichten: List<Bericht>, wachtend: Boolean = false) {
        assertEquals(if (wachtend) listOf("$k:ontvanger") else emptyList(), sleutelsOnder(k))
        assertEquals(wachtend, indexScore() != null, "index-entry: ${indexScore()}")
        berichten.forEach { assertFalse(bestaat(BerichtenCache.berichtKey(it.sleutel)), "${it.sleutel} achtergebleven") }
        assertEquals(0L, zoekTotaal(ontvanger))
    }

    private fun sleutelsOnder(prefix: String): List<String> =
        redis.execute("KEYS", "$prefix:*").await().atMost(WACHTTIJD).map { it.toString() }

    private fun indexScore(): Double? =
        redis.sortedSet(String::class.java).zscore(RedisSessieRegister.VERLOOP_INDEX, BerichtenCache.kenmerk(ontvanger)).await().atMost(WACHTTIJD)

    private fun bestaat(sleutel: String): Boolean = redis.key().exists(sleutel).await().atMost(WACHTTIJD)

    private fun pttl(sleutel: String): Long = redis.key().pttl(sleutel).await().atMost(WACHTTIJD)

    private fun zoekTotaal(voor: Identificatienummer): Long =
        redis.search().ftSearch(BerichtenCache.SEARCH_INDEX, "@ontvanger:{${voor.waarde}}")
            .await().atMost(WACHTTIJD).count().toLong()

    private fun bericht() = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = WireMockMagazijnResource.OIN_A,
        afzenderNaam = "Magazijn A",
        ontvanger = ontvanger,
        onderwerp = "Opruimtest",
        publicatietijdstip = Instant.parse("2026-10-21T10:00:00Z"),
        magazijnId = WireMockMagazijnResource.OIN_A,
        aantalBijlagen = 0,
    )

    private companion object {
        val WACHTTIJD: Duration = Duration.ofSeconds(5)
        const val WACHTTIJD_S = 10L
        const val RONDE_S = 15L
        const val MAX_DUUR_MS = 20_000L
        const val DATA_TTL_MS = 21_000L
        const val LOCK_TTL_MS = 5_000L
        const val AANTAL_OVER_GRENS = 1001
        private val teller = AtomicLong(System.nanoTime())

        fun nieuweOntvanger() = Oin(teller.incrementAndGet().toString().padStart(20, '0').takeLast(20))
    }
}
