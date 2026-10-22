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
import jakarta.ws.rs.WebApplicationException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ApiInfo
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
import org.junit.jupiter.params.provider.CsvSource
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
 * Wat een afnemer ziet als de uitvraag een bijlage doorgeeft: dat een grote bijlage echt streamt,
 * wat een afgebroken overdracht oplevert, en dat de magazijn-response in elk geval dichtgaat. Het
 * magazijn is [BijlageMagazijnStub]. De [ProcessingHandler] is vervangen, met een vaste span voor de
 * logregel: zo kan de test zien wat de logregel meekrijgt, dat er geen ERROR-child komt, en dat de
 * applicatielog naar de juiste logregel verwijst.
 *
 * Elke gestreamde download hier moet zijn plek onder het plafond teruggeven; doet hij dat niet, dan
 * krijgen de tests erna een 503.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
@QuarkusTestResource(value = BijlageMagazijnStub::class, restrictToAnnotatedClass = true)
@Timeout(60)
class BijlageStreamenTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    @Inject
    lateinit var streams: Bijlagestreams

    @TestHTTPResource("/api/v1/berichten")
    lateinit var berichten: URL

    private val processingHandler: ProcessingHandler = mockk(relaxed = true)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val berichtId = UUID.randomUUID()
    private val bijlageId = UUID.randomUUID()

    private val records = CopyOnWriteArrayList<LogRecord>()
    private val loggers = listOf(BijlageDoorgifte::class.java, BerichtOphaalService::class.java, UncaughtExceptionMapper::class.java)
        .map { Logger.getLogger(it.name) }
    private val vorigeNiveaus = loggers.associateWith { it.level }

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
        loggers.forEach {
            it.removeHandler(handler)
            it.level = vorigeNiveaus[it]
        }

        BijlageMagazijnStub.reset()

        // Elke gestreamde download hoort zijn plek terug te geven, ook als hij afbrak.
        val tot = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

        while (streams.aantalOpen > 0 && System.nanoTime() < tot) Thread.sleep(20)

        assertEquals(0, streams.aantalOpen, "een download hield zijn plek onder het plafond vast")
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

    /** Wacht op een melding; de writer van de uitvraag logt als laatste, vóór de reset. */
    private fun wachtOpMelding(logger: Class<*>, deel: String): Pair<Level, String> {
        val tot = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)

        while (System.nanoTime() < tot) {
            meldingen(logger).firstOrNull { deel in it.second }?.let { return it }
            Thread.sleep(20)
        }

        error("geen melding met '$deel' — was: ${meldingen(logger)}")
    }

    /** De logregel zelf bleef zonder fout, er kwam geen ERROR-child, en niets op error als "onverwacht". */
    private fun logregelZonderFoutEnNietsOnverwachts() {
        // De mapper draait pas na de reset; even wachten, anders valt een ontbrekende mapper niet op.
        Thread.sleep(500)

        verify(exactly = 1) { processingHandler.addLogboekContextToSpan(any(), any(), isNull()) }
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Logregel>(), any()) }
        assertTrue(meldingen(UncaughtExceptionMapper::class.java).isEmpty(), "${meldingen(UncaughtExceptionMapper::class.java)}")
    }

    // Onder het begin gaat de bijlage als byte[] de deur uit, altijd met lengte; erboven streamt ze,
    // en draagt ze alleen een lengte als het magazijn die gaf.
    @ParameterizedTest(name = "{0} bytes, met Content-Length van het magazijn: {1}")
    @CsvSource(
        "0, true",
        "1, true",
        "1, false",
        "1048575, true",
        "1048575, false",
        "1048576, true",
        "1048576, false",
        "1048577, false",
        "4194321, true",
        "4194321, false",
    )
    fun `een bijlage komt byte voor byte door, volgens het contract`(grootte: Int, metLengte: Boolean) {
        val inhoud = Random.nextBytes(grootte)
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(grootte.toLong().takeIf { metLengte }) { it.write(inhoud) }
        val verwachteLengte = grootte.toString().takeIf { metLengte || grootte < BijlageInhoud.BEGIN_BYTES }

        // De validator kent geen lege body onder een schema; een lege bijlage is wel geldig.
        val antwoord = given()
            .apply { if (grootte > 0) filter(CONTRACT) }
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get(adres)
            .then()
            .statusCode(200)
            .header("Content-Type", equalTo("application/pdf"))
            .header("Content-Length", if (verwachteLengte != null) equalTo(verwachteLengte) else nullValue())
            .header("API-Version", equalTo(ApiInfo.API_VERSION))
            .extract()

        assertArrayEquals(inhoud, antwoord.asByteArray())
        logregelZonderFoutEnNietsOnverwachts()
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
        val (niveau, melding) = wachtOpMelding(BijlageDoorgifte::class.java, "overdracht afgebroken")
        assertEquals(Level.SEVERE.intValue(), niveau.intValue())
        assertTrue("[${LOGBOEK_SPAN.traceId}:${LOGBOEK_SPAN.spanId}]" in melding, melding)
        assertTrue("berichtId=$berichtId" in melding, melding)
        assertTrue("magazijnId=${BijlageMagazijnStub.OIN}" in melding, "de OIN hoort voluit in de log — $melding")
        assertFalse("999990019" in melding, "geen BSN in de log — $melding")
        // De afnemer kreeg al een deel: de verstrekking blijft staan, liever te veel dan te weinig.
        logregelZonderFoutEnNietsOnverwachts()
    }

    // Zonder lengte beslist alleen "fout of einde van de stream" of dit een 502 wordt of een
    // ingekorte bijlage; net onder het begin is het scherpste geval.
    @ParameterizedTest(name = "met Content-Length: {0}, {1} bytes geschreven")
    @CsvSource("true, 1024", "false, 1024", "false, 1048575")
    fun `breekt het magazijn in het begin af, dan krijgt de afnemer een 502 volgens het contract`(metLengte: Boolean, geschreven: Int) {
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(GROOT.toLong().takeIf { metLengte }) { uit ->
            uit.write(ByteArray(geschreven))
            uit.flush()
            // Pas afbreken als de uitvraag het antwoord al heeft: dan faalt het lezen van het
            // begin, en niet de aanroep zelf.
            Thread.sleep(300)

            if (!metLengte) throw BijlageMagazijnStub.Afbreken()
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

        val (_, melding) = wachtOpMelding(BerichtOphaalService::class.java, "body-read mislukt")
        assertTrue("[${LOGBOEK_SPAN.traceId}:${LOGBOEK_SPAN.spanId}]" in melding, melding)

        // De fout ontstaat nog binnen de @Logboek-methode: de logregel krijgt hem zelf mee.
        verify(exactly = 1) {
            processingHandler.addLogboekContextToSpan(any(), any(), match<Throwable> { it is WebApplicationException && it.response.status == 502 })
        }

        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
    }

    // Fail-closed: mislukt het schrijven van de logregel, dan gooit de wrapper ná de methode. De
    // writer draait dan niet, en toch moeten de plek en de magazijn-response vrijkomen.
    @Test
    fun `mislukt de logregel na het begin, dan komen de plek en de magazijn-response toch vrij`() {
        every { processingHandler.enforceWriteAcknowledgement(any()) } throws IllegalStateException("logboek weg")
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(HEEL_GROOT.toLong()) { it.write(ByteArray(HEEL_GROOT)) }

        val antwoord = http.send(verzoek(), BodyHandlers.ofString())

        assertEquals(500, antwoord.statusCode())
        assertEquals(BijlageMagazijnStub.Afloop.VOLLEDIG_GESCHREVEN, BijlageMagazijnStub.afloopVoor(berichtId), "de uitvraag hoort de magazijn-response te sluiten")
    }

    @Test
    fun `een afnemer die de download afbreekt, is geen fout van de verwerking en laat het magazijn niet hangen`() {
        val afnemerWeg = CountDownLatch(1)

        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage((VOORSCHOT + HEEL_GROOT).toLong()) { uit ->
            uit.write(ByteArray(VOORSCHOT))
            uit.flush()

            // Pas verder als de afnemer weg is: dan zit de uitvraag gegarandeerd midden in de rest,
            // en is die rest te groot om zonder sluiten in de buffers weg te vloeien.
            afnemerWeg.await(10, TimeUnit.SECONDS)
            uit.write(ByteArray(HEEL_GROOT))
        }

        val antwoord = http.send(verzoek(), BodyHandlers.ofInputStream())
        antwoord.body().use { it.readNBytes(BijlageInhoud.BEGIN_BYTES) }
        afnemerWeg.countDown()

        val (niveau, _) = wachtOpMelding(BijlageDoorgifte::class.java, "brak de bijlage-download af")
        assertEquals(Level.FINE.intValue(), niveau.intValue(), "een geannuleerde download hoort op debug")
        assertEquals(BijlageMagazijnStub.Afloop.VOLLEDIG_GESCHREVEN, BijlageMagazijnStub.afloopVoor(berichtId), "de uitvraag hoort de magazijn-response te sluiten")
        logregelZonderFoutEnNietsOnverwachts()
    }

    @Test
    fun `een HEAD-verzoek op een grote bijlage geeft de headers en laat de magazijn-response niet openstaan`() {
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(HEEL_GROOT.toLong()) { it.write(ByteArray(HEEL_GROOT)) }

        val antwoord = http.send(verzoek("HEAD"), BodyHandlers.discarding())

        assertEquals(200, antwoord.statusCode())
        assertEquals(HEEL_GROOT.toString(), antwoord.headers().firstValue("Content-Length").orElse(null))
        assertEquals("application/pdf", antwoord.headers().firstValue("Content-Type").orElse(null))
        assertEquals("inline", antwoord.headers().firstValue("Content-Disposition").orElse(null))
        assertEquals(ApiInfo.API_VERSION, antwoord.headers().firstValue("API-Version").orElse(null))
        assertEquals(BijlageMagazijnStub.Afloop.VOLLEDIG_GESCHREVEN, BijlageMagazijnStub.afloopVoor(berichtId), "de uitvraag hoort de magazijn-response te sluiten")
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

        assertEquals(BijlageMagazijnStub.Afloop.VOLLEDIG_GESCHREVEN, BijlageMagazijnStub.afloopVoor(berichtId, 15), "de uitvraag hoort de magazijn-response te sluiten")
    }

    private companion object {
        /** Ruim boven het begin dat de uitvraag inleest. */
        const val GROOT = 4 * 1024 * 1024 + 17

        /** Groter dan de socketbuffers: zonder sluiten komt het magazijn hier nooit doorheen. */
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
