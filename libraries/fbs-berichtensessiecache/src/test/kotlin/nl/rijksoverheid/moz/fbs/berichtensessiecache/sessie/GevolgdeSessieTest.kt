package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.helpers.test.AssertSubscriber
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AggregationStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenCache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.SessieGebeurtenis
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * `_volgen` op een inlogsessie, tegen een echte Redis en via de facade: de hartslag raakt de sessie,
 * en een sessie die niet meer loopt, sluit de stream. Duren uit [GevolgdeSessieRedisTestProfile]:
 * inactiviteit 2 s, hartslag 0,5 s.
 */
@QuarkusTest
@TestProfile(GevolgdeSessieRedisTestProfile::class)
class GevolgdeSessieTest {

    @Inject
    lateinit var sessiecache: Sessiecache

    @Inject
    internal lateinit var berichtenCache: BerichtenCache

    // Uniek per test: de Redis-container wordt gedeeld tussen de tests van dit profiel.
    private val ontvanger = Oin(teller.incrementAndGet().toString().padStart(20, '0').takeLast(20))

    @Test
    fun `volgen met een onbekende sessie gooit SessieOngeldig vóór de stream`() {
        sessiecache.startSessie(ontvanger)
        zetGereed()

        assertThrows<SessiecacheException.SessieOngeldig> { sessiecache.volg(ontvanger, ONBEKENDE_ID) }
    }

    @Test
    fun `volgen eindigt met SessieVerlopen nadat de sessie elders is beëindigd`() {
        val sessie = sessiecache.startSessie(ontvanger).sessieId
        zetGereed()
        val volgend = volg(sessie)
        wachtTot("VolgenGestart") { SessieGebeurtenis.VolgenGestart in volgend.items }

        sessiecache.beeindigSessie(sessie, ontvanger)

        volgend.awaitCompletion(WACHTTIJD)
        assertEquals(SessieGebeurtenis.SessieVerlopen, volgend.items.last())
    }

    @Test
    fun `hartslag houdt sessie langer dan inactiviteit in leven`() {
        // Twee sessies tegelijk gestart; alleen de gevolgde wordt geraakt. Zonder die tweede zou de
        // test ook slagen als sessies hier gewoon niet verliepen.
        val gevolgd = sessiecache.startSessie(ontvanger).sessieId
        val ongevolgd = sessiecache.startSessie(ontvanger).sessieId
        zetGereed()
        val volgend = volg(gevolgd)

        Thread.sleep(INACTIVITEIT.multipliedBy(2).toMillis())

        assertThrows<SessiecacheException.SessieOngeldig> { sessiecache.raakSessie(ongevolgd, ontvanger) }
        assertDoesNotThrow { sessiecache.raakSessie(gevolgd, ontvanger) }
        assertFalse(SessieGebeurtenis.SessieVerlopen in volgend.items, "stream meldde verlopen: ${volgend.items}")
        assertTrue(volgend.items.count { it == SessieGebeurtenis.Hartslag } >= 2, "te weinig hartslagen: ${volgend.items}")

        volgend.cancel()
    }

    private fun volg(sessieId: String): AssertSubscriber<SessieGebeurtenis> =
        sessiecache.volg(ontvanger, sessieId).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))

    // Na de start: een start zonder andere lopende sessie ruimt eerst de resten op, ook deze status.
    private fun zetGereed() {
        berichtenCache.storeAggregationStatus(
            BerichtenCache.cacheKey(ontvanger),
            AggregationStatus(OphalenStatus.GEREED, totaalMagazijnen = 1, geslaagd = 1),
        ).await().atMost(WACHTTIJD)
    }

    private fun wachtTot(wat: String, voorwaarde: () -> Boolean) {
        val deadline = System.nanoTime() + WACHTTIJD.toNanos()

        while (!voorwaarde()) {
            check(System.nanoTime() < deadline) { "$wat niet gezien binnen $WACHTTIJD" }

            Thread.sleep(10)
        }
    }

    private companion object {
        val WACHTTIJD: Duration = Duration.ofSeconds(5)
        val INACTIVITEIT: Duration = Duration.ofSeconds(2)
        const val ONBEKENDE_ID = "AAAAAAAAAAAAAAAAAAAAAA"
        private val teller = AtomicLong(System.nanoTime())
    }
}
