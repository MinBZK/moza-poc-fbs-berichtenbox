package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Een magazijn dat midden in een bijlage stilvalt, houdt zijn connection niet onbeperkt vast. De
 * read-timeout van de client stopt zodra de headers binnen zijn; daarna begrenst alleen
 * `magazijn-router.bijlage-idle-timeout` het, hier op twee seconden.
 */
@QuarkusTest
@TestProfile(BijlageStilstandTest.KorteIdleTimeout::class)
@QuarkusTestResource(value = BijlageMagazijnStub::class, restrictToAnnotatedClass = true)
@Timeout(60)
class BijlageStilstandTest {

    class KorteIdleTimeout : QuarkusTestProfile {
        private val basis = MockSessiecacheProfile()

        override fun getEnabledAlternatives(): Set<Class<*>> = basis.enabledAlternatives

        override fun getConfigOverrides(): Map<String, String> =
            basis.configOverrides + ("magazijn-router.bijlage-idle-timeout" to "PT2S")
    }

    @Inject
    lateinit var sessiecache: MockSessiecache

    @TestHTTPResource("/api/v1/berichten")
    lateinit var berichten: URL

    private val stilstandVoorbij = CountDownLatch(1)

    @AfterEach
    fun ruimOp() {
        stilstandVoorbij.countDown()
        BijlageMagazijnStub.reset()
    }

    @Test
    fun `een magazijn dat midden in een bijlage stilvalt, breekt de download af na de idle-timeout`() {
        val berichtId = UUID.randomUUID()
        sessiecache.zet(
            Bericht(
                berichtId = berichtId,
                afzender = BijlageMagazijnStub.OIN,
                afzenderNaam = "Stubmagazijn",
                ontvanger = Bsn("999990019"),
                onderwerp = "X",
                publicatietijdstip = Instant.parse("2026-05-26T10:00:00Z"),
                magazijnId = BijlageMagazijnStub.OIN,
                aantalBijlagen = 0,
            ),
        )

        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(GROOT.toLong()) { uit ->
            uit.write(ByteArray(BijlageInhoud.BEGIN_BYTES + 256 * 1024))
            uit.flush()
            stilstandVoorbij.await(30, TimeUnit.SECONDS)
        }

        val verzoek = HttpRequest.newBuilder(URI.create("$berichten/$berichtId/bijlagen/${UUID.randomUUID()}?magazijnId=${BijlageMagazijnStub.OIN}"))
            .header("X-Ontvanger", "BSN:999990019")
            .timeout(Duration.ofSeconds(20))
            .build()
        val start = System.nanoTime()

        val fout = assertThrows(IOException::class.java) { HttpClient.newHttpClient().send(verzoek, BodyHandlers.ofByteArray()) }

        val duur = Duration.ofNanos(System.nanoTime() - start)
        assertFalse(fout is HttpTimeoutException, "de uitvraag hoort af te breken, niet te blijven hangen")
        assertTrue(duur < Duration.ofSeconds(10), "afgebroken na $duur; de idle-timeout is twee seconden")
    }

    private companion object {
        const val GROOT = 4 * 1024 * 1024
    }
}
