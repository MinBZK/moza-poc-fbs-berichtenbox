package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.common.exception.UncaughtExceptionMapper
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.jboss.logmanager.formatters.PatternFormatter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.random.Random

/**
 * Wat een afnemer ziet als de uitvraag een bijlage doorgeeft: dat er echt gestreamd wordt, wat een
 * afgebroken overdracht oplevert, en dat de magazijn-response in elk geval dichtgaat. Het magazijn
 * is [BijlageMagazijnStub]. De [ProcessingHandler] is vervangen, met een vaste span voor de
 * logregel: zo kan de test zien dat er geen ERROR-child komt, en dat de applicatielog naar de
 * juiste logregel verwijst.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
@QuarkusTestResource(value = BijlageMagazijnStub::class, restrictToAnnotatedClass = true)
@Timeout(60)
class BijlageStreamenTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    @TestHTTPResource("/api/v1/berichten")
    lateinit var berichten: URL

    private val processingHandler: ProcessingHandler = mockk(relaxed = true)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val berichtId = UUID.randomUUID()
    private val bijlageId = UUID.randomUUID()

    private val records = CopyOnWriteArrayList<LogRecord>()
    private val loggers = listOf(UitvraagResource::class.java, UncaughtExceptionMapper::class.java).map { Logger.getLogger(it.name) }

    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records.add(record)
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    private val adres: String get() = "$berichten/$berichtId/bijlagen/$bijlageId?magazijnId=${BijlageMagazijnStub.OIN}"

    @BeforeEach
    fun zetKlaar() {
        BijlageMagazijnStub.reset()
        sessiecache.reset()
        every { processingHandler.startSpan(any(), any()) } returns Span.wrap(LOGBOEK_SPAN)
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

        loggers.forEach {
            it.addHandler(handler)
            it.level = Level.ALL
        }
    }

    @AfterEach
    fun ruimOp() {
        loggers.forEach { it.removeHandler(handler) }
        BijlageMagazijnStub.reset()
    }

    private fun verzoek(methode: String = "GET"): HttpRequest =
        HttpRequest.newBuilder(URI.create(adres))
            .header("X-Ontvanger", "BSN:999990019")
            .timeout(Duration.ofSeconds(20))
            .method(methode, HttpRequest.BodyPublishers.noBody())
            .build()

    private fun meldingen(logger: Class<*>): List<Pair<Level, String>> = records
        .filter { it.loggerName == logger.name }
        .map { it.level to BERICHT.format(it) }

    /** Wacht tot de writer van de uitvraag klaar is: die logt als laatste, vóór de reset. */
    private fun wachtOpMelding(logger: Class<*>, deel: String): Pair<Level, String> {
        val tot = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)

        while (System.nanoTime() < tot) {
            meldingen(logger).firstOrNull { deel in it.second }?.let { return it }
            Thread.sleep(20)
        }

        error("geen melding met '$deel' — was: ${meldingen(logger)}")
    }

    private fun geenErrorChildEnGeenOnverwachteFout() {
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Logregel>(), any()) }
        assertTrue(meldingen(UncaughtExceptionMapper::class.java).isEmpty(), "${meldingen(UncaughtExceptionMapper::class.java)}")
    }

    @ParameterizedTest(name = "met Content-Length: {0}")
    @ValueSource(booleans = [true, false])
    fun `een grote bijlage komt byte voor byte door, volgens het contract`(metLengte: Boolean) {
        val inhoud = Random.nextBytes(GROOT)
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(inhoud.size.toLong().takeIf { metLengte }) { it.write(inhoud) }

        val antwoord = given()
            .filter(CONTRACT)
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get(adres)
            .then()
            .statusCode(200)
            .header("Content-Type", equalTo("application/pdf"))
            .header("Content-Length", if (metLengte) equalTo(inhoud.size.toString()) else nullValue())
            .extract()

        assertArrayEquals(inhoud, antwoord.asByteArray())
        geenErrorChildEnGeenOnverwachteFout()
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

        antwoord.body().use { stream ->
            // Minder dan wat het magazijn al gaf: de outputbuffer van Quarkus houdt het laatste
            // stukje vast tot er meer komt.
            val begin = stream.readNBytes(BijlageInhoud.BEGIN_BYTES)
            restMag.countDown()
            val rest = stream.readAllBytes()

            assertArrayEquals(inhoud, begin + rest)
        }
    }

    // Zonder Content-Length (chunked) houdt alleen de reset een ingekorte bijlage tegen: een netjes
    // afgesloten antwoord zou er voor de afnemer volledig uitzien.
    @ParameterizedTest(name = "met Content-Length: {0}")
    @ValueSource(booleans = [true, false])
    fun `breekt het magazijn halverwege af, dan krijgt de afnemer een onvolledige download`(metLengte: Boolean) {
        val inhoud = Random.nextBytes(GROOT)

        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(inhoud.size.toLong().takeIf { metLengte }) { uit ->
            uit.write(inhoud, 0, VOORSCHOT)
            uit.flush()

            if (!metLengte) throw BijlageMagazijnStub.Afbreken()
        }

        val fout = assertThrows(IOException::class.java) { http.send(verzoek(), BodyHandlers.ofByteArray()) }

        assertFalse(fout is HttpTimeoutException, "de uitvraag hoort af te breken, niet te blijven hangen")
        val (niveau, melding) = wachtOpMelding(UitvraagResource::class.java, "overdracht afgebroken")
        assertEquals(Level.SEVERE.intValue(), niveau.intValue())
        assertTrue("[${LOGBOEK_SPAN.traceId}:${LOGBOEK_SPAN.spanId}]" in melding, melding)
        assertTrue("berichtId=$berichtId" in melding, melding)
        // De afnemer kreeg al een deel: de verstrekking blijft staan, liever te veel dan te weinig.
        geenErrorChildEnGeenOnverwachteFout()
    }

    @Test
    fun `breekt het magazijn in het begin af, dan krijgt de afnemer een 502 volgens het contract`() {
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(GROOT.toLong()) { uit ->
            uit.write(ByteArray(1024))
            uit.flush()
            // Pas afbreken als de uitvraag het antwoord al heeft: dan faalt het lezen van het
            // begin, en niet de aanroep zelf.
            Thread.sleep(300)
        }

        given()
            .filter(CONTRACT)
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get(adres)
            .then()
            .statusCode(502)
            .header("Content-Type", equalTo("application/problem+json"))
            .header("Content-Disposition", nullValue())
            .body("type", equalTo("urn:fbs:fout:keten-fout"))

        // De fout ontstaat nog binnen de @Logboek-methode; de logregel krijgt hem zelf mee.
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
    }

    @Test
    fun `een afnemer die de download afbreekt, is geen fout van de verwerking en laat het magazijn niet hangen`() {
        val inhoud = Random.nextBytes(GROOT)
        val afnemerWeg = CountDownLatch(1)

        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(inhoud.size.toLong()) { uit ->
            uit.write(inhoud, 0, VOORSCHOT)
            uit.flush()

            // Pas verder als de afnemer weg is: dan zit de uitvraag gegarandeerd midden in de rest.
            afnemerWeg.await(10, TimeUnit.SECONDS)
            uit.write(inhoud, VOORSCHOT, inhoud.size - VOORSCHOT)
        }

        val antwoord = http.send(verzoek(), BodyHandlers.ofInputStream())
        antwoord.body().use { it.readNBytes(BijlageInhoud.BEGIN_BYTES) }
        afnemerWeg.countDown()

        val (niveau, _) = wachtOpMelding(UitvraagResource::class.java, "afnemer")
        assertTrue(niveau.intValue() < Level.SEVERE.intValue(), "een afgebroken download hoort niet op error — was: $niveau")
        assertTrue(BijlageMagazijnStub.afgehandeld.poll(10, TimeUnit.SECONDS) != null, "de uitvraag hoort de magazijn-response te sluiten")
        geenErrorChildEnGeenOnverwachteFout()
    }

    @Test
    fun `een HEAD-verzoek geeft de headers en laat de magazijn-response niet openstaan`() {
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(HEEL_GROOT.toLong()) { it.write(ByteArray(HEEL_GROOT)) }

        val antwoord = http.send(verzoek("HEAD"), BodyHandlers.discarding())

        assertEquals(200, antwoord.statusCode())
        assertEquals(HEEL_GROOT.toString(), antwoord.headers().firstValue("Content-Length").orElse(null))
        assertEquals("application/pdf", antwoord.headers().firstValue("Content-Type").orElse(null))
        assertEquals("inline", antwoord.headers().firstValue("Content-Disposition").orElse(null))
        assertTrue(BijlageMagazijnStub.afgehandeld.poll(10, TimeUnit.SECONDS) != null, "de uitvraag hoort de magazijn-response te sluiten")
    }

    // De end-handler komt pas na het ophalen van het begin; een afnemer die dan al weg is, zet hem
    // niet meer in gang.
    @ParameterizedTest
    @ValueSource(strings = ["HEAD", "GET"])
    fun `een afnemer die vertrekt voordat het magazijn antwoordt, laat de magazijn-response niet openstaan`(methode: String) {
        val ontvangen = CountDownLatch(1)
        val afnemerWeg = CountDownLatch(1)

        BijlageMagazijnStub.gedrag = { exchange ->
            ontvangen.countDown()
            afnemerWeg.await(10, TimeUnit.SECONDS)
            BijlageMagazijnStub.bijlage(HEEL_GROOT.toLong()) { it.write(ByteArray(HEEL_GROOT)) }(exchange)
        }

        Socket(berichten.host, berichten.port).use { socket ->
            val pad = URI.create(adres).let { "${it.rawPath}?${it.rawQuery}" }
            val verzoekregels = "$methode $pad HTTP/1.1\r\nHost: localhost\r\nX-Ontvanger: BSN:999990019\r\n\r\n"
            socket.getOutputStream().write(verzoekregels.toByteArray())
            socket.getOutputStream().flush()

            // Pas sluiten als de uitvraag het magazijn al aanroept; anders komt ze er niet aan toe.
            assertTrue(ontvangen.await(10, TimeUnit.SECONDS), "het verzoek hoort het magazijn te bereiken")
        }

        // Even wachten, zodat de uitvraag het sluiten heeft gezien voordat het magazijn antwoordt.
        Thread.sleep(300)
        afnemerWeg.countDown()

        assertTrue(BijlageMagazijnStub.afgehandeld.poll(15, TimeUnit.SECONDS) != null, "de uitvraag hoort de magazijn-response te sluiten")
    }

    private companion object {
        /** Ruim boven het begin dat de uitvraag inleest, en boven de socketbuffers. */
        const val GROOT = 4 * 1024 * 1024 + 17
        const val HEEL_GROOT = 16 * 1024 * 1024

        /** Meer dan het begin: de uitvraag streamt dan al, en heeft nog wat over om door te geven. */
        const val VOORSCHOT = BijlageInhoud.BEGIN_BYTES + 256 * 1024

        val LOGBOEK_SPAN: SpanContext = SpanContext.create(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            TraceFlags.getSampled(),
            TraceState.getDefault(),
        )

        /** Alleen de geformatteerde melding: Quarkus formatteert een logregel pas bij het uitschrijven. */
        val BERICHT = PatternFormatter("%s")

        val CONTRACT = OpenApiValidationFilter(
            OpenApiInteractionValidator.createForSpecificationUrl("openapi/berichtenuitvraag-api.yaml").build(),
        )
    }
}
