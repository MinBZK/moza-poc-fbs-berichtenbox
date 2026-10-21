package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.helpers.test.AssertSubscriber
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieEinde
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieRegister
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieStart
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.TestSessieConfig
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Met in-memory buren opgebouwd, zodat elke test zijn eigen hartslag en sessiestaat heeft.
class SessieVolgerTest {

    private val cache = MockBerichtenCache()
    private val aanmeldingen = MockAanmeldingen()
    private val sessies = GeheugenSessieRegister()
    private val volger = SessieVolger(cache, aanmeldingen, sessies, HARTSLAG, MAX_DUUR, INACTIVITEIT_TWEE_MINUTEN)

    // Alleen voor de tests die de hartslag zelf toetsen. Elders zou een tik tussen twee stappen van
    // een test vallen en de telling van de items verstoren.
    private val snel = SessieVolger(cache, aanmeldingen, sessies, SNELLE_HARTSLAG, MAX_DUUR, INACTIVITEIT_TWEE_MINUTEN)

    private val ontvanger = Bsn("999993653")
    private val ander = Bsn("999990019")

    @Test
    fun `begint met VolgenGestart bij een lopende sessie`() {
        startSessie(ontvanger)

        val volgend = volg(ontvanger)

        volgend.wachtTot { it.isNotEmpty() }
        assertEquals(SessieGebeurtenis.VolgenGestart, volgend.items.first())
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
    fun `twee magazijnen met hetzelfde berichtnummer komen allebei door`() {
        startSessie(ontvanger)
        val volgend = volg(ontvanger).also { it.wachtTot { items -> items.isNotEmpty() } }

        val vanA = bericht(ontvanger)
        val vanB = vanA.copy(magazijnId = "00000001003214345001", onderwerp = "van B")
        meldAan(vanA)
        meldAan(vanB)

        volgend.wachtTot { items -> items.count { it is SessieGebeurtenis.BerichtBijgekomen } == 2 }
        assertEquals(
            listOf(SessieGebeurtenis.BerichtBijgekomen(vanA), SessieGebeurtenis.BerichtBijgekomen(vanB)),
            volgend.zonderHartslag().drop(1),
        )
    }

    @Test
    fun `een melding waarvan het bericht al weg is, wordt overgeslagen`() {
        startSessie(ontvanger)
        val volgend = volg(ontvanger).also { it.wachtTot { items -> items.isNotEmpty() } }

        aanmeldingen.meld(BerichtenCache.cacheKey(ontvanger), BerichtSleutel("00000001003214345000", UUID.randomUUID())).await().indefinitely()

        assertEquals(listOf(SessieGebeurtenis.VolgenGestart), volgend.zonderHartslag())
    }

    @Test
    fun `de hartslag raakt de gevolgde sessie en meldt zich zolang die loopt`() {
        startSessie(ontvanger)

        val volgend = snel.volg(ontvanger, SESSIE).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))

        volgend.wachtTot { it.size >= 3 }
        assertEquals(listOf(SessieGebeurtenis.Hartslag, SessieGebeurtenis.Hartslag), volgend.items.drop(1).take(2))
        assertTrue(sessies.aanrakingen.all { it == (SESSIE to ontvanger) }, "geraakt: ${sessies.aanrakingen}")
    }

    @Test
    fun `volgen eindigt met SessieVerlopen nadat de sessie elders is beëindigd`() {
        startSessie(ontvanger)
        val volgend = snel.volg(ontvanger, SESSIE).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))
            .also { it.wachtTot { items -> items.isNotEmpty() } }

        sessies.lopend.remove(SESSIE to ontvanger)

        volgend.awaitCompletion(Duration.ofSeconds(5))
        assertEquals(SessieGebeurtenis.SessieVerlopen, volgend.items.last())
        wachtTotAfgemeld(BerichtenCache.cacheKey(ontvanger))
    }

    @Test
    fun `een andere lopende sessie houdt de gevolgde niet in leven`() {
        // De hartslag gaat over precies deze sessie van precies deze ontvanger: een tweede tabblad
        // dat nog ingelogd is, of dezelfde id bij een ander, verlengt hem niet.
        startSessie(ontvanger)
        sessies.lopend += ANDERE_SESSIE to ontvanger
        sessies.lopend += SESSIE to ander
        val volgend = snel.volg(ontvanger, SESSIE).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))
            .also { it.wachtTot { items -> items.isNotEmpty() } }

        sessies.lopend.remove(SESSIE to ontvanger)

        volgend.awaitCompletion(Duration.ofSeconds(5))
        assertEquals(SessieGebeurtenis.SessieVerlopen, volgend.items.last())
    }

    @Test
    fun `is de sessie bij het starten al weg, dan is SessieVerlopen het enige item`() {
        // De facade controleert de sessie vóór de stream; dit is het geval dat ze daarna, maar vóór
        // VolgenGestart, eindigt. Ook dan gaat het om precies deze sessie.
        sessies.lopend += ANDERE_SESSIE to ontvanger
        sessies.lopend += SESSIE to ander

        val volgend = volg(ontvanger)

        volgend.awaitCompletion()
        assertEquals(listOf(SessieGebeurtenis.SessieVerlopen), volgend.items)
        wachtTotAfgemeld(BerichtenCache.cacheKey(ontvanger))
    }

    @Test
    fun `mislukt het aanraken op een hartslag, dan breekt de stream af in plaats van SessieVerlopen te melden`() {
        // Een storing is geen verlopen sessie: SessieVerlopen zou de gebruiker laten uitloggen. Een
        // hartslag melden toont een levende sessie die intussen afloopt. Afbreken laat de afnemer
        // opnieuw verbinden, en die krijgt dan een eerlijke 503.
        val fout = IllegalStateException("EVAL geweigerd")
        startSessie(ontvanger)
        // De controle bij het starten slaagt nog; de eerste hartslag niet.
        sessies.raakFoutNa(1, fout)

        val volgend = snel.volg(ontvanger, SESSIE).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))

        volgend.awaitFailure(Duration.ofSeconds(5))
        assertEquals(fout, volgend.failure)
        assertEquals(listOf(SessieGebeurtenis.VolgenGestart), volgend.items, "geen hartslag of SessieVerlopen na een mislukte aanraking")
        wachtTotAfgemeld(BerichtenCache.cacheKey(ontvanger))
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
    fun `stopt de pod, dan eindigt de stream zonder fout en zonder SessieVerlopen`() {
        // Een rolling update is geen storing: de afnemer verbindt opnieuw, net als na de maximale duur.
        startSessie(ontvanger)
        val volgend = volg(ontvanger).also { it.wachtTot { items -> items.isNotEmpty() } }

        aanmeldingen.stop()

        volgend.awaitCompletion()
        assertEquals(SessieGebeurtenis.VolgenGestart, volgend.items.first())
        assertFalse(SessieGebeurtenis.SessieVerlopen in volgend.items, "de sessie loopt nog: ${volgend.items}")
    }

    @Test
    fun `stopt de pod nog vóór VolgenGestart, dan eindigt de stream leeg en zonder fout`() {
        // "Hoogstens één VolgenGestart": dit is het geval met nul. De afnemer verbindt opnieuw.
        startSessie(ontvanger)
        aanmeldingen.actiefHangt = true
        val volgend = volg(ontvanger)

        aanmeldingen.stop()

        volgend.awaitCompletion()
        assertEquals(emptyList<SessieGebeurtenis>(), volgend.items)
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
            override fun getById(sleutel: BerichtSleutel, ontvanger: Identificatienummer): Uni<Bericht?> =
                Uni.createFrom().failure(IllegalStateException("redis weg"))
        }

        val volgerMetFout = SessieVolger(falendeCache, aanmeldingen, sessies, HARTSLAG, MAX_DUUR, INACTIVITEIT_TWEE_MINUTEN)
        startSessie(ontvanger)
        val volgend = volgerMetFout.volg(ontvanger, SESSIE).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))
        volgend.wachtTot { it.isNotEmpty() }

        aanmeldingen.meld(BerichtenCache.cacheKey(ontvanger), BerichtSleutel("00000001003214345000", UUID.randomUUID())).await().indefinitely()

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
        val sleutel = BerichtSleutel("00000001003214345000", UUID.randomUUID())

        volger.meldAan(ontvanger, sleutel).await().indefinitely()

        assertEquals(listOf(BerichtenCache.cacheKey(ontvanger) to sleutel), aanmeldingen.meldingen)
    }

    @ParameterizedTest(name = "hartslag {0}")
    @ValueSource(strings = ["PT0S", "PT-1S"])
    fun `een hartslag van nul of minder weigert te starten`(hartslag: String) {
        val fout = assertThrows<IllegalArgumentException> {
            SessieVolger(cache, aanmeldingen, sessies, Duration.parse(hartslag), MAX_DUUR, INACTIVITEIT_TWEE_MINUTEN)
        }

        assertTrue(fout.message!!.contains("moet groter zijn dan 0"), fout.message)
    }

    /**
     * De grens zelf (precies de helft) en een waarde erboven. Een ruime maximale duur, zodat niet
     * de controle daarop eerst omvalt: dan zou deze test slagen zonder de regel te toetsen die hij
     * noemt. De TTL op de gegevens ligt bij deze config op 13 uur; een controle daartegen zou beide
     * waarden toelaten.
     */
    @ParameterizedTest(name = "hartslag {0} bij een inactiviteit van 2 minuten")
    @ValueSource(strings = ["PT1M", "PT90S"])
    fun `een hartslag vanaf de helft van de inactiviteit weigert te starten`(hartslag: String) {
        val fout = assertThrows<IllegalArgumentException> {
            SessieVolger(cache, aanmeldingen, sessies, Duration.parse(hartslag), Duration.ofHours(1), INACTIVITEIT_TWEE_MINUTEN)
        }

        assertTrue(
            fout.message!!.contains("berichtensessiecache.volg-hartslag (${Duration.parse(hartslag)}) moet kleiner zijn dan de helft van berichtensessiecache.sessie.inactiviteit"),
            fout.message,
        )
    }

    @Test
    fun `een hartslag net onder de helft van de inactiviteit start wel`() {
        SessieVolger(cache, aanmeldingen, sessies, Duration.ofSeconds(59), Duration.ofHours(1), INACTIVITEIT_TWEE_MINUTEN)
    }

    @Test
    fun `na de maximale duur eindigt de stream zonder SessieVerlopen, zodat de afnemer opnieuw verbindt`() {
        startSessie(ontvanger)
        val kort = SessieVolger(cache, aanmeldingen, sessies, SNELLE_HARTSLAG, Duration.ofMillis(700), INACTIVITEIT_TWEE_MINUTEN)

        val volgend = kort.volg(ontvanger, SESSIE).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))

        volgend.awaitCompletion(Duration.ofSeconds(5))
        assertTrue(SessieGebeurtenis.SessieVerlopen !in volgend.items)
        wachtTotAfgemeld(BerichtenCache.cacheKey(ontvanger))
    }

    @ParameterizedTest(name = "max-duur {0}")
    @ValueSource(strings = ["PT30S", "PT1S"])
    fun `een maximale duur die niet boven de hartslag ligt, weigert te starten`(maxDuur: String) {
        val fout = assertThrows<IllegalArgumentException> {
            SessieVolger(cache, aanmeldingen, sessies, HARTSLAG, Duration.parse(maxDuur), INACTIVITEIT_TWEE_MINUTEN)
        }

        assertTrue(fout.message!!.contains("berichtensessiecache.volg-max-duur"))
    }

    private fun volg(wie: Identificatienummer): AssertSubscriber<SessieGebeurtenis> =
        volger.volg(wie, SESSIE).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))

    private fun AssertSubscriber<SessieGebeurtenis>.zonderHartslag() = items.filterNot { it == SessieGebeurtenis.Hartslag }

    /**
     * `awaitItems(n)` faalt zodra er méér dan `n` items zijn, en op een trage runner kan er al een
     * volgend item binnen zijn. Hier telt alleen of de voorwaarde op enig moment waar wordt.
     */
    private fun AssertSubscriber<SessieGebeurtenis>.wachtTot(voorwaarde: (List<SessieGebeurtenis>) -> Boolean) =
        wachtOp({ "voorwaarde niet gehaald binnen 5 s; items: $items" }) { voorwaarde(items) }

    /**
     * De emitter meldt het einde aan de afnemer vóórdat hij `onTermination` draait, en daar zit de
     * afmelding. Eindigt de stream op een andere thread dan die van de test, dan is de luisteraar
     * direct na `awaitCompletion` of `awaitFailure` nog niet per se afgemeld.
     */
    private fun wachtTotAfgemeld(cacheKey: String) =
        wachtOp({ "luisteraar niet afgemeld binnen 5 s; nog ${aanmeldingen.aantalLuisteraars(cacheKey)}" }) {
            aanmeldingen.aantalLuisteraars(cacheKey) == 0
        }

    private fun wachtOp(melding: () -> String, voorwaarde: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()

        while (!voorwaarde()) {
            if (System.nanoTime() >= deadline) fail<Unit>(melding())

            Thread.sleep(10)
        }
    }

    private fun startSessie(wie: Identificatienummer) {
        sessies.lopend += SESSIE to wie
    }

    private fun meldAan(bericht: Bericht) {
        cache.createBericht(bericht, bericht.ontvanger).await().indefinitely()
        volger.meldAan(bericht.ontvanger, bericht.sleutel).await().indefinitely()
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

    /** Lopende sessies in het geheugen; een sessie loopt tot de test hem weghaalt. */
    private class GeheugenSessieRegister : SessieRegister {
        val lopend: MutableSet<Pair<String, Identificatienummer>> = ConcurrentHashMap.newKeySet()
        val aanrakingen: MutableList<Pair<String, Identificatienummer>> = Collections.synchronizedList(mutableListOf())

        @Volatile
        private var fout: Throwable? = null

        @Volatile
        private var foutNa = Int.MAX_VALUE

        /** Na [aantal] geslaagde aanrakingen faalt elke volgende met [fout]. */
        fun raakFoutNa(aantal: Int, fout: Throwable) {
            this.fout = fout
            foutNa = aantal
        }

        override fun raak(sessieId: String, ontvanger: Identificatienummer): Uni<Boolean> {
            aanrakingen += sessieId to ontvanger
            val storing = fout?.takeIf { aanrakingen.size > foutNa }

            return storing?.let { Uni.createFrom().failure(it) }
                ?: Uni.createFrom().item((sessieId to ontvanger) in lopend)
        }

        override fun start(ontvanger: Identificatienummer): Uni<SessieStart?> = throw UnsupportedOperationException()

        override fun beeindig(sessieId: String, ontvanger: Identificatienummer): Uni<SessieEinde> = throw UnsupportedOperationException()

        override fun ruimOpZonderSessie(cacheKey: String): Uni<Boolean> = throw UnsupportedOperationException()

        override fun verlopen(max: Int): Uni<List<Identificatienummer>> = throw UnsupportedOperationException()

        override fun veeg(ontvanger: Identificatienummer): Uni<Boolean> = throw UnsupportedOperationException()
    }

    private companion object {
        val HARTSLAG: Duration = Duration.ofSeconds(30)
        val SNELLE_HARTSLAG: Duration = Duration.ofMillis(200)
        val MAX_DUUR: Duration = Duration.ofMinutes(1)

        const val SESSIE = "sessie-van-deze-berichtenbox"
        const val ANDERE_SESSIE = "sessie-van-een-ander-tabblad"

        val INACTIVITEIT_TWEE_MINUTEN = TestSessieConfig(inactiviteit = Duration.ofMinutes(2))
    }
}
