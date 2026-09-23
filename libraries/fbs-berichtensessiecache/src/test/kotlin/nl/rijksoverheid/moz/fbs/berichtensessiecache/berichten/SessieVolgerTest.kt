package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.helpers.test.AssertSubscriber
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import java.util.UUID

// @QuarkusTest zodat de coverage in jacoco-quarkus.exec terechtkomt; de volger zelf is met
// in-memory buren opgebouwd zodat elke test zijn eigen hartslag en sessiestaat heeft.
@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class SessieVolgerTest {

    private val cache = MockBerichtenCache()
    private val aanmeldingen = MockAanmeldingen()
    private val volger = SessieVolger(cache, aanmeldingen, HARTSLAG, MAX_DUUR, Duration.ofMinutes(2))

    // Alleen voor de tests die de hartslag zelf toetsen. Elders zou een tik tussen twee stappen van
    // een test vallen en de telling van de items verstoren.
    private val snel = SessieVolger(cache, aanmeldingen, SNELLE_HARTSLAG, MAX_DUUR, Duration.ofMinutes(2))

    private val ontvanger = Bsn("999993653")
    private val ander = Bsn("999990019")

    @Test
    fun `begint met VolgenGestart bij een lopende sessie`() {
        startSessie(ontvanger)

        val volgend = volg(ontvanger)

        volgend.wachtTot { it.isNotEmpty() }
        assertEquals(SessieGebeurtenis.VolgenGestart, volgend.items.first())
    }

    @Test
    fun `zonder sessie meldt de stream SessieVerlopen en eindigt`() {
        val volgend = volg(ontvanger)

        volgend.awaitCompletion()
        assertEquals(listOf(SessieGebeurtenis.SessieVerlopen), volgend.items)
    }

    @ParameterizedTest(name = "{0} aangemelde berichten")
    @ValueSource(ints = [0, 1, 3])
    fun `stuurt elk aangemeld bericht van de eigen sessie door`(aantal: Int) {
        startSessie(ontvanger)
        val volgend = volg(ontvanger).also { it.wachtTot { items -> items.isNotEmpty() } }

        val berichten = List(aantal) { bericht(ontvanger) }
        berichten.forEach { meldAan(it) }

        val bijgekomen = volgend.items.filterIsInstance<SessieGebeurtenis.BerichtBijgekomen>()
        assertEquals(berichten, bijgekomen.map { it.bericht })
    }

    @Test
    fun `een bericht voor een andere ontvanger komt niet door`() {
        startSessie(ontvanger)
        startSessie(ander)
        val volgend = volg(ontvanger).also { it.wachtTot { items -> items.isNotEmpty() } }

        meldAan(bericht(ander))

        assertEquals(listOf(SessieGebeurtenis.VolgenGestart), volgend.zonderHartslag())
    }

    @Test
    fun `twee open berichtenboxen van dezelfde ontvanger krijgen allebei het bericht`() {
        startSessie(ontvanger)
        val eerste = volg(ontvanger).also { it.wachtTot { items -> items.isNotEmpty() } }
        val tweede = volg(ontvanger).also { it.wachtTot { items -> items.isNotEmpty() } }

        val nieuw = bericht(ontvanger)
        meldAan(nieuw)

        listOf(eerste, tweede).forEach { volgend ->
            assertEquals(SessieGebeurtenis.BerichtBijgekomen(nieuw), volgend.zonderHartslag().last())
        }
    }

    @Test
    fun `een melding waarvan het bericht al weg is, wordt overgeslagen`() {
        startSessie(ontvanger)
        val volgend = volg(ontvanger).also { it.wachtTot { items -> items.isNotEmpty() } }

        aanmeldingen.meld(BerichtenCache.cacheKey(ontvanger), UUID.randomUUID()).await().indefinitely()

        assertEquals(listOf(SessieGebeurtenis.VolgenGestart), volgend.zonderHartslag())
    }

    @Test
    fun `de hartslag meldt zich zolang de sessie loopt`() {
        startSessie(ontvanger)

        val volgend = snel.volg(ontvanger).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))

        volgend.wachtTot { it.size >= 3 }
        assertEquals(listOf(SessieGebeurtenis.Hartslag, SessieGebeurtenis.Hartslag), volgend.items.drop(1).take(2))
    }

    @Test
    fun `verloopt de sessie tussendoor, dan volgt SessieVerlopen en eindigt de stream`() {
        startSessie(ontvanger)
        val volgend = snel.volg(ontvanger).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE)).also { it.wachtTot { items -> items.isNotEmpty() } }

        cache.clear()

        volgend.awaitCompletion(Duration.ofSeconds(5))
        assertEquals(SessieGebeurtenis.SessieVerlopen, volgend.items.last())
    }

    @Test
    fun `mislukt het verlengen op een hartslag, dan breekt de stream af in plaats van een hartslag te melden`() {
        // Een hartslag die doorgaat terwijl verlengen mislukt, toont een levende sessie die intussen
        // afloopt. Afbreken laat de afnemer opnieuw verbinden, en die krijgt dan een eerlijke 503.
        val fout = IllegalStateException("MULTI geweigerd")
        var verlengingen = 0
        val haperendeCache = object : BerichtenCache by cache {
            override fun verlengSessie(key: String): Uni<Boolean> =
                if (verlengingen++ == 0) Uni.createFrom().item(true) else Uni.createFrom().failure(fout)
        }

        val haperend = SessieVolger(haperendeCache, aanmeldingen, SNELLE_HARTSLAG, MAX_DUUR, Duration.ofMinutes(2))
        startSessie(ontvanger)
        val volgend = haperend.volg(ontvanger).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))

        volgend.awaitFailure(Duration.ofSeconds(5))
        assertEquals(fout, volgend.failure)
        assertEquals(listOf(SessieGebeurtenis.VolgenGestart), volgend.items, "geen hartslag na een mislukte verlenging")
    }

    @Test
    fun `valt het doorgeven weg, dan eindigt de stream met die fout`() {
        startSessie(ontvanger)
        val volgend = volg(ontvanger).also { it.wachtTot { items -> items.isNotEmpty() } }
        val fout = IllegalStateException("abonnement weg")

        aanmeldingen.valWeg(fout)

        volgend.awaitFailure()
        assertEquals(fout, volgend.failure)
    }

    @Test
    fun `lukt de activering niet, dan breekt de stream af zonder VolgenGestart`() {
        // VolgenGestart zegt tegen de afnemer "lees nu de lijst, vanaf hier krijg je alles". Komt het
        // toch zonder werkend abonnement, dan wacht hij daarna tot de maximale duur op berichten
        // die nooit komen.
        startSessie(ontvanger)
        val fout = IllegalStateException("abonnement niet rond")
        aanmeldingen.actiefFout = fout

        val volgend = volg(ontvanger)

        volgend.awaitFailure()
        assertEquals(fout, volgend.failure)
        assertEquals(emptyList<SessieGebeurtenis>(), volgend.items)
        assertEquals(0, aanmeldingen.aantalLuisteraars(BerichtenCache.cacheKey(ontvanger)), "de luisteraar hoort afgemeld te zijn")
    }

    @Test
    fun `een leesfout op een aangemeld bericht breekt de stream af`() {
        val falendeCache = object : BerichtenCache by cache {
            override fun getById(berichtId: UUID, ontvanger: Identificatienummer): Uni<Bericht?> =
                Uni.createFrom().failure(IllegalStateException("redis weg"))
        }

        val volgerMetFout = SessieVolger(falendeCache, aanmeldingen, HARTSLAG, MAX_DUUR, Duration.ofMinutes(2))
        startSessie(ontvanger)
        val volgend = volgerMetFout.volg(ontvanger).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))
        volgend.wachtTot { it.isNotEmpty() }

        aanmeldingen.meld(BerichtenCache.cacheKey(ontvanger), UUID.randomUUID()).await().indefinitely()

        volgend.awaitFailure()
    }

    @Test
    fun `een gesloten berichtenbox luistert niet meer mee`() {
        startSessie(ontvanger)
        val volgend = volg(ontvanger).also { it.wachtTot { items -> items.isNotEmpty() } }
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        assertEquals(1, aanmeldingen.aantalLuisteraars(cacheKey))

        volgend.cancel()

        assertEquals(0, aanmeldingen.aantalLuisteraars(cacheKey))
    }

    @Test
    fun `meldAan meldt onder de cacheKey van de ontvanger`() {
        val berichtId = UUID.randomUUID()

        volger.meldAan(ontvanger, berichtId).await().indefinitely()

        assertEquals(listOf(BerichtenCache.cacheKey(ontvanger) to berichtId), aanmeldingen.meldingen)
    }

    @ParameterizedTest(name = "hartslag {0}")
    @ValueSource(strings = ["PT0S", "PT-1S"])
    fun `een hartslag van nul of minder weigert te starten`(hartslag: String) {
        val fout = assertThrows<IllegalArgumentException> {
            SessieVolger(cache, aanmeldingen, Duration.parse(hartslag), MAX_DUUR, Duration.ofMinutes(2))
        }

        assertTrue(fout.message!!.contains("moet groter zijn dan 0"), fout.message)
    }

    /**
     * De grens zelf (precies de helft) en een waarde erboven. Een ruime maximale duur, zodat niet
     * de controle daarop eerst omvalt: dan zou deze test slagen zonder de regel te toetsen die hij
     * noemt.
     */
    @ParameterizedTest(name = "hartslag {0} bij een sessieduur van 2 minuten")
    @ValueSource(strings = ["PT1M", "PT90S"])
    fun `een hartslag vanaf de helft van de sessieduur weigert te starten`(hartslag: String) {
        val fout = assertThrows<IllegalArgumentException> {
            SessieVolger(cache, aanmeldingen, Duration.parse(hartslag), Duration.ofHours(1), Duration.ofMinutes(2))
        }

        assertTrue(fout.message!!.contains("moet kleiner zijn dan 1/2 van berichtensessiecache.ttl"), fout.message)
    }

    @Test
    fun `een hartslag net onder de helft van de sessieduur start wel`() {
        SessieVolger(cache, aanmeldingen, Duration.ofSeconds(59), Duration.ofHours(1), Duration.ofMinutes(2))
    }

    @Test
    fun `na de maximale duur eindigt de stream zonder SessieVerlopen, zodat de afnemer opnieuw verbindt`() {
        startSessie(ontvanger)
        val kort = SessieVolger(cache, aanmeldingen, SNELLE_HARTSLAG, Duration.ofMillis(700), Duration.ofMinutes(2))

        val volgend = kort.volg(ontvanger).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))

        volgend.awaitCompletion(Duration.ofSeconds(5))
        assertTrue(SessieGebeurtenis.SessieVerlopen !in volgend.items)
        assertEquals(0, aanmeldingen.aantalLuisteraars(BerichtenCache.cacheKey(ontvanger)))
    }

    @ParameterizedTest(name = "max-duur {0}")
    @ValueSource(strings = ["PT30S", "PT1S"])
    fun `een maximale duur die niet boven de hartslag ligt, weigert te starten`(maxDuur: String) {
        val fout = assertThrows<IllegalArgumentException> {
            SessieVolger(cache, aanmeldingen, HARTSLAG, Duration.parse(maxDuur), Duration.ofMinutes(2))
        }

        assertTrue(fout.message!!.contains("berichtensessiecache.volg-max-duur"))
    }

    private fun volg(wie: Identificatienummer): AssertSubscriber<SessieGebeurtenis> =
        volger.volg(wie).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))

    private fun AssertSubscriber<SessieGebeurtenis>.zonderHartslag() = items.filterNot { it == SessieGebeurtenis.Hartslag }

    /**
     * `awaitItems(n)` faalt zodra er méér dan `n` items zijn, en op een trage runner kan er al een
     * volgend item binnen zijn. Hier telt alleen of de voorwaarde op enig moment waar wordt.
     */
    private fun AssertSubscriber<SessieGebeurtenis>.wachtTot(voorwaarde: (List<SessieGebeurtenis>) -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()

        while (!voorwaarde(items)) {
            check(System.nanoTime() < deadline) { "voorwaarde niet gehaald binnen 5 s; items: $items" }
            Thread.sleep(10)
        }
    }

    private fun startSessie(wie: Identificatienummer) {
        val key = BerichtenCache.cacheKey(wie)
        cache.store(key, emptyList()).await().indefinitely()
        cache.storeAggregationStatus(key, AggregationStatus(OphalenStatus.GEREED, totaalMagazijnen = 1, geslaagd = 1))
            .await().indefinitely()
    }

    private fun meldAan(bericht: Bericht) {
        cache.createBericht(bericht, bericht.ontvanger).await().indefinitely()
        volger.meldAan(bericht.ontvanger, bericht.berichtId).await().indefinitely()
    }

    private fun bericht(wie: Identificatienummer) = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = "00000001003214345000",
        afzenderNaam = "Magazijn A",
        ontvanger = wie,
        onderwerp = "Nieuw bericht",
        publicatietijdstip = Instant.parse("2026-09-21T10:00:00Z"),
        magazijnId = "00000001003214345000",
        aantalBijlagen = 0,
    )

    private companion object {
        val HARTSLAG: Duration = Duration.ofSeconds(30)
        val SNELLE_HARTSLAG: Duration = Duration.ofMillis(200)
        val MAX_DUUR: Duration = Duration.ofMinutes(1)
    }
}
