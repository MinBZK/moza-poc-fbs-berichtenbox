package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.common.exception.UncaughtExceptionMapper
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import org.hamcrest.Matchers.equalTo
import org.jboss.logmanager.formatters.PatternFormatter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.io.InputStream
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

/**
 * De grenzen op een gestreamde bijlage-download, met kleine waarden: twee seconden stilstand, twee
 * streams op de pod en één per ontvanger.
 */
@QuarkusTest
@TestProfile(BijlageGrenzenTest.KleineGrenzen::class)
@QuarkusTestResource(value = BijlageMagazijnStub::class, restrictToAnnotatedClass = true)
@Timeout(60)
class BijlageGrenzenTest {

    class KleineGrenzen : QuarkusTestProfile {
        private val basis = MockSessiecacheProfile()

        override fun getEnabledAlternatives(): Set<Class<*>> = basis.enabledAlternatives

        override fun getConfigOverrides(): Map<String, String> = basis.configOverrides + mapOf(
            "berichtenuitvraag.bijlage.max-stilstand" to "PT2S",
            "berichtenuitvraag.bijlage.max-streams" to "2",
            "berichtenuitvraag.bijlage.max-streams-per-ontvanger" to "1",
        )
    }

    @Inject
    lateinit var sessiecache: MockSessiecache

    @TestHTTPResource("/api/v1/berichten")
    lateinit var berichten: URL

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val restMag = CountDownLatch(1)
    private val ontvangers = listOf("999990019", "999990032", "999990044")
    private val berichtVoor = ontvangers.associateWith { UUID.randomUUID() }

    private val records = CopyOnWriteArrayList<LogRecord>()
    private val loggers = listOf(BijlageDoorgifte::class.java, UncaughtExceptionMapper::class.java).map { Logger.getLogger(it.name) }
    private val vorigeNiveaus = loggers.associateWith { it.level }

    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records.add(record)
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    @BeforeEach
    fun zetKlaar() {
        BijlageMagazijnStub.reset()
        sessiecache.reset()

        berichtVoor.forEach { (bsn, berichtId) ->
            sessiecache.zet(
                Bericht(
                    berichtId = berichtId,
                    afzender = BijlageMagazijnStub.OIN,
                    afzenderNaam = "Stubmagazijn",
                    ontvanger = Bsn(bsn),
                    onderwerp = "X",
                    publicatietijdstip = Instant.parse("2026-05-26T10:00:00Z"),
                    magazijnId = BijlageMagazijnStub.OIN,
                    aantalBijlagen = 0,
                ),
            )
        }

        loggers.forEach {
            it.addHandler(handler)
            it.level = Level.ALL
        }
    }

    @AfterEach
    fun ruimOp() {
        restMag.countDown()
        loggers.forEach {
            it.removeHandler(handler)
            it.level = vorigeNiveaus[it]
        }

        BijlageMagazijnStub.reset()
    }

    private fun adres(bsn: String) =
        "$berichten/${berichtVoor.getValue(bsn)}/bijlagen/${UUID.randomUUID()}?magazijnId=${BijlageMagazijnStub.OIN}"

    private fun verzoek(bsn: String): HttpRequest =
        HttpRequest.newBuilder(URI.create(adres(bsn)))
            .header("X-Ontvanger", "BSN:$bsn")
            .timeout(Duration.ofSeconds(20))
            .build()

    private fun wachtOpMelding(deel: String): Pair<Level, String> {
        val tot = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)

        while (System.nanoTime() < tot) {
            records.filter { it.loggerName == BijlageDoorgifte::class.java.name }
                .map { it.level to BERICHT.format(it) }
                .firstOrNull { deel in it.second }
                ?.let { return it }
            Thread.sleep(20)
        }

        error("geen melding met '$deel'")
    }

    /** Een magazijn dat het begin geeft en de rest vasthoudt tot de test hem vrijgeeft. */
    private fun houdtDeRestVast() {
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(GROOT.toLong()) { uit ->
            uit.write(ByteArray(VOORSCHOT))
            uit.flush()

            if (restMag.await(20, TimeUnit.SECONDS)) uit.write(ByteArray(GROOT - VOORSCHOT))
        }
    }

    /** Start een download die blijft lopen, en geeft de stream terug zodra het begin binnen is. */
    private fun lopendeDownload(bsn: String): InputStream {
        val stream = http.send(verzoek(bsn), BodyHandlers.ofInputStream()).body()
        stream.readNBytes(BijlageInhoud.BEGIN_BYTES)

        return stream
    }

    private fun geweigerd(bsn: String) {
        given()
            .filter(CONTRACT)
            .header("X-Ontvanger", "BSN:$bsn")
            .`when`()
            .get(adres(bsn))
            .then()
            .statusCode(503)
            .header("Retry-After", equalTo("30"))
            .body("type", equalTo("urn:fbs:fout:te-veel-gelijktijdige-downloads"))
    }

    @Test
    fun `een magazijn dat midden in een bijlage stilvalt, breekt de download af na de stilstandgrens`() {
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(GROOT.toLong()) { uit ->
            uit.write(ByteArray(VOORSCHOT))
            uit.flush()
            restMag.await(30, TimeUnit.SECONDS)
        }

        val start = System.nanoTime()

        val fout = assertThrows(IOException::class.java) { http.send(verzoek(ontvangers[0]), BodyHandlers.ofByteArray()) }

        val duur = Duration.ofNanos(System.nanoTime() - start)
        assertFalse(fout is HttpTimeoutException, "de uitvraag hoort af te breken, niet te blijven hangen")
        assertTrue(duur >= Duration.ofMillis(1800), "afgebroken na $duur; eerder dan de stilstandgrens")
        assertTrue(duur < Duration.ofSeconds(10), "afgebroken na $duur; de stilstandgrens is twee seconden")
        assertEquals(Level.SEVERE.intValue(), wachtOpMelding("overdracht afgebroken").first.intValue())
    }

    @Test
    fun `een afnemer die niet leest, houdt de download niet langer vast dan de stilstandgrens`() {
        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(HEEL_GROOT.toLong()) { it.write(ByteArray(HEEL_GROOT)) }

        Socket(berichten.host, berichten.port).use { socket ->
            val pad = URI.create(adres(ontvangers[0])).let { "${it.rawPath}?${it.rawQuery}" }
            socket.getOutputStream().write("GET $pad HTTP/1.1\r\nHost: localhost\r\nX-Ontvanger: BSN:${ontvangers[0]}\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()

            // Niets lezen: de uitvraag loopt vast op een volle socket, tot de bewaking ingrijpt.
            val (niveau, melding) = wachtOpMelding("las langer dan")

            assertEquals(Level.WARNING.intValue(), niveau.intValue(), melding)
        }

        assertNotNull(BijlageMagazijnStub.afloopVoor(berichtVoor.getValue(ontvangers[0])), "de uitvraag hoort de magazijn-response te sluiten")
        assertTrue(records.none { it.loggerName == UncaughtExceptionMapper::class.java.name })
    }

    @Test
    fun `boven het plafond per ontvanger volgt een 503, en na afloop is er weer plek`() {
        houdtDeRestVast()

        lopendeDownload(ontvangers[0]).use { lopend ->
            geweigerd(ontvangers[0])
            restMag.countDown()
            lopend.readAllBytes()
        }

        BijlageMagazijnStub.gedrag = BijlageMagazijnStub.bijlage(GROOT.toLong()) { it.write(ByteArray(GROOT)) }

        // De plek komt vrij als het antwoord eindigt, en dat kan net na de laatste byte zijn.
        val tot = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var status: Int

        do {
            status = http.send(verzoek(ontvangers[0]), BodyHandlers.discarding()).statusCode()
        } while (status == 503 && System.nanoTime() < tot)

        assertEquals(200, status, "na afloop hoort de plek weer vrij te zijn")
    }

    @Test
    fun `boven het plafond van de pod volgt een 503, ook voor een ontvanger zonder download`() {
        houdtDeRestVast()

        lopendeDownload(ontvangers[0]).use { eerste ->
            lopendeDownload(ontvangers[1]).use { tweede ->
                geweigerd(ontvangers[2])
                restMag.countDown()
                eerste.readAllBytes()
                tweede.readAllBytes()
            }
        }
    }

    private companion object {
        const val GROOT = 4 * 1024 * 1024
        const val HEEL_GROOT = 48 * 1024 * 1024
        const val VOORSCHOT = BijlageInhoud.BEGIN_BYTES + 256 * 1024

        val BERICHT = PatternFormatter("%s")

        val CONTRACT = OpenApiValidationFilter(
            OpenApiInteractionValidator.createForSpecificationUrl("openapi/berichtenuitvraag-api.yaml").build(),
        )
    }
}
