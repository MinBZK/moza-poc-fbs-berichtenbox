package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.DataSubject
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Wat een afnemer ziet als de uitvraag een bijlage doorgeeft: dat er echt gestreamd wordt, en wat
 * een afgebroken overdracht oplevert, aan de afnemer en in het logboek. Het magazijn is
 * [BijlageMagazijnStub]; de [ProcessingHandler] is per test vervangen, zodat de test kan lezen wat
 * er als mislukte uitkomst wordt vastgelegd.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
@QuarkusTestResource(value = BijlageMagazijnStub::class, restrictToAnnotatedClass = true)
class BijlageStreamenTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    @TestHTTPResource("/api/v1/berichten")
    lateinit var berichten: URL

    private val processingHandler: ProcessingHandler = mockk(relaxed = true)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val berichtId = UUID.randomUUID()
    private val bijlageId = UUID.randomUUID()

    @BeforeEach
    fun zetKlaar() {
        sessiecache.reset()
        QuarkusMock.installMockForType(processingHandler, ProcessingHandler::class.java)
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
    }

    private fun verzoek(methode: String = "GET"): HttpRequest =
        HttpRequest.newBuilder(URI.create("$berichten/$berichtId/bijlagen/$bijlageId?magazijnId=${BijlageMagazijnStub.OIN}"))
            .header("X-Ontvanger", "BSN:999990019")
            .timeout(Duration.ofSeconds(20))
            .method(methode, HttpRequest.BodyPublishers.noBody())
            .build()

    @Test
    fun `een grote bijlage komt byte voor byte door, met haar lengte`() {
        val inhoud = Random.nextBytes(GROOT)
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(inhoud.size.toLong()) { it.write(inhoud) }

        val antwoord = http.send(verzoek(), BodyHandlers.ofByteArray())

        assertEquals(200, antwoord.statusCode())
        assertEquals("application/pdf", antwoord.headers().firstValue("Content-Type").orElse(null))
        assertEquals(inhoud.size.toString(), antwoord.headers().firstValue("Content-Length").orElse(null))
        assertArrayEquals(inhoud, antwoord.body())
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<List<Logregel>>(), any()) }
    }

    @Test
    fun `de afnemer krijgt het begin terwijl het magazijn de rest nog vasthoudt`() {
        val inhoud = Random.nextBytes(GROOT)
        val restMag = CountDownLatch(1)
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(inhoud.size.toLong()) { uit ->
            uit.write(inhoud, 0, VOORSCHOT)
            uit.flush()

            // Buffert de uitvraag de hele bijlage, dan komt de test hier nooit voorbij het begin.
            // De stub breekt dan na de wachttijd af, en de test faalt op een onvolledige download.
            if (restMag.await(10, TimeUnit.SECONDS)) uit.write(inhoud, VOORSCHOT, inhoud.size - VOORSCHOT)
        }

        val antwoord = http.send(verzoek(), BodyHandlers.ofInputStream())

        antwoord.body().use { stroom ->
            // Minder dan wat het magazijn al gaf: de outputbuffer van Quarkus houdt het laatste
            // stukje vast tot er meer komt.
            val begin = stroom.readNBytes(VOORSCHOT / 2)
            restMag.countDown()
            val rest = stroom.readAllBytes()

            assertArrayEquals(inhoud, begin + rest)
        }
    }

    @Test
    fun `breekt het magazijn halverwege af, dan krijgt de afnemer een onvolledige download en het logboek een mislukking`() {
        val inhoud = Random.nextBytes(GROOT)
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(inhoud.size.toLong()) { it.write(inhoud, 0, VOORSCHOT) }

        assertThrows(IOException::class.java) { http.send(verzoek(), BodyHandlers.ofByteArray()) }

        val logregel = slot<List<Logregel>>()
        verify(timeout = 5_000, exactly = 1) { processingHandler.recordFailedOutcome(capture(logregel), any()) }
        val vastgelegd = logregel.captured.single()
        assertEquals("uitvraag-bijlage", vastgelegd.name)
        assertEquals(ProcessingActivities.UITVRAAG_LEZEN, vastgelegd.processingActivityId)
        assertEquals(DataSubject("999990019", "BSN"), vastgelegd.subject)
    }

    @Test
    fun `haalt de mislukking het logboek niet, dan krijgt de afnemer nog steeds een onvolledige download`() {
        val inhoud = Random.nextBytes(GROOT)
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(inhoud.size.toLong()) { it.write(inhoud, 0, VOORSCHOT) }
        every { processingHandler.recordFailedOutcome(any<List<Logregel>>(), any()) } throws IllegalStateException("logboek weg")

        assertThrows(IOException::class.java) { http.send(verzoek(), BodyHandlers.ofByteArray()) }

        verify(timeout = 5_000, exactly = 1) { processingHandler.recordFailedOutcome(any<List<Logregel>>(), any()) }
    }

    @Test
    fun `breekt het magazijn in het begin af, dan krijgt de afnemer een 502 zonder de headers van de bijlage`() {
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(GROOT.toLong()) { uit ->
            uit.write(ByteArray(1024))
            uit.flush()
            // Pas afbreken als de uitvraag het antwoord al heeft: dan faalt het lezen van het
            // begin, en niet de aanroep zelf.
            Thread.sleep(300)
        }

        val antwoord = http.send(verzoek(), BodyHandlers.ofString())

        assertEquals(502, antwoord.statusCode())
        assertEquals("application/problem+json", antwoord.headers().firstValue("Content-Type").orElse(null))
        assertNull(antwoord.headers().firstValue("Content-Disposition").orElse(null))
        // De fout ontstaat nog binnen de @Logboek-methode; de logregel krijgt hem zelf mee.
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<List<Logregel>>(), any()) }
    }

    @Test
    fun `een afnemer die de download afbreekt, is geen mislukte verwerking en laat het magazijn niet hangen`() {
        val inhoud = Random.nextBytes(GROOT)
        val magazijnKlaar = CountDownLatch(1)
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(inhoud.size.toLong()) { uit ->
            uit.write(inhoud)
            magazijnKlaar.countDown()
        }

        val antwoord = http.send(verzoek(), BodyHandlers.ofInputStream())
        antwoord.body().use { it.readNBytes(VOORSCHOT) }

        assertTrue(magazijnKlaar.await(10, TimeUnit.SECONDS), "de uitvraag hoort de magazijn-response te sluiten")
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<List<Logregel>>(), any()) }
    }

    @Test
    fun `een HEAD-verzoek laat de magazijn-response niet openstaan`() {
        val magazijnKlaar = CountDownLatch(1)
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(HEEL_GROOT.toLong()) { uit ->
            uit.write(ByteArray(HEEL_GROOT))
            magazijnKlaar.countDown()
        }

        val antwoord = http.send(verzoek("HEAD"), BodyHandlers.discarding())

        assertEquals(200, antwoord.statusCode())
        assertTrue(magazijnKlaar.await(10, TimeUnit.SECONDS), "de uitvraag hoort de magazijn-response te sluiten")
    }

    private companion object {
        /** Ruim boven het begin dat de uitvraag inleest, en boven de socketbuffers. */
        const val GROOT = 4 * 1024 * 1024 + 17
        const val HEEL_GROOT = 16 * 1024 * 1024
        const val VOORSCHOT = 256 * 1024
    }
}
