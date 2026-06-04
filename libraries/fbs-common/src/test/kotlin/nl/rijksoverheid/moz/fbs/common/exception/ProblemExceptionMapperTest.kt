package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.text.MessageFormat
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class ProblemExceptionMapperTest {

    private val mapper = ProblemExceptionMapper()

    private val julLogger: Logger = Logger.getLogger(ProblemExceptionMapper::class.java.name)
    private val records = mutableListOf<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records.add(record)
        }
        override fun flush() {}
        override fun close() {}
    }

    @BeforeEach
    fun installHandler() {
        julLogger.addHandler(handler)
        julLogger.level = Level.ALL
        records.clear()
    }

    @AfterEach
    fun removeHandler() {
        julLogger.removeHandler(handler)
    }

    @Test
    fun `behoudt 4xx detail zodat client weet wat er mis is`() {
        val response = mapper.toResponse(NotFoundException("bericht niet gevonden"))

        assertEquals(404, response.status)
        val problem = response.entity as Problem
        assertEquals(404, problem.status)
        assertEquals("Not Found", problem.title)
        assertEquals("bericht niet gevonden", problem.detail)
        // 4xx krijgt een correlation-id voor support-traceability (zonder detail te maskeren).
        assertNotNull(problem.instance)
        assertTrue(problem.instance!!.toString().startsWith("urn:uuid:"))
    }

    @Test
    fun `saneert stacktrace-achtige 4xx detail`() {
        val response = mapper.toResponse(
            BadRequestException("Input fout at nl.rijksoverheid.moz.Foo.bar(Foo.kt:42) op regel 5"),
        )

        val problem = response.entity as Problem
        assertFalse(problem.detail!!.contains("Foo.kt:42"), "file+line moet weg: ${problem.detail}")
        assertFalse(problem.detail.contains("at nl.rijksoverheid"), "frame moet weg: ${problem.detail}")
    }

    @Test
    fun `maskeert 5xx detail en voegt correlation-id toe`() {
        val response = mapper.toResponse(InternalServerErrorException("SELECT * FROM users; stacktrace lek"))

        assertEquals(500, response.status)
        val problem = response.entity as Problem
        assertEquals(500, problem.status)
        assertNotEquals("SELECT * FROM users; stacktrace lek", problem.detail)
        assertNotNull(problem.detail)
        assertTrue(problem.detail!!.contains("errorId"))
        assertNotNull(problem.instance)
        assertTrue(problem.instance!!.toString().startsWith("urn:uuid:"))
    }

    @Test
    fun `geeft 4xx een correlation-id in instance voor support-traceability`() {
        val response = mapper.toResponse(BadRequestException("ongeldig"))

        val problem = response.entity as Problem
        // 4xx krijgt een errorId zodat support een concrete request kan terugvinden bij klacht.
        // Detail wordt niet gemaskeerd (zoals bij 5xx) — "ongeldig" blijft zichtbaar.
        assertEquals("ongeldig", problem.detail)
        assertNotNull(problem.instance)
    }

    @Test
    fun `4xx-log bevat GEEN exception-message (PII-protectie)`() {
        // Borgt de Round 9-fix: 4xx-log laat exception.message bewust weg
        // omdat FoutBeschrijving.saneer geen niet-numerieke PII (namen, e-mail)
        // dekt. Refactor die `infov(... exception.message ...)` terugzet zou
        // BSN/namen in applicatielog laten lekken — deze test vangt dat.
        val piiMessage = "Ontvanger Jan de Vries (jan@example.nl) ongeldig"

        mapper.toResponse(BadRequestException(piiMessage))

        val infoRecords = records.filter { it.level == Level.INFO }
        assertTrue(infoRecords.isNotEmpty(), "verwacht 1 info-regel voor 4xx")
        // `infov` gebruikt MessageFormat-stijl (`{0}`), niet printf.
        val output = infoRecords.joinToString("\n") { rec ->
            rec.parameters?.let { runCatching { MessageFormat.format(rec.message, *it) }.getOrDefault(rec.message) }
                ?: rec.message
        }
        assertFalse(
            output.contains(piiMessage),
            "PII-message mag NIET in 4xx-log staan — gevonden: $output",
        )
        assertFalse(
            output.contains("jan@example.nl"),
            "e-mail PII mag NIET in 4xx-log — gevonden: $output",
        )
        assertTrue(
            output.contains("BadRequestException"),
            "type-correlatie moet aanwezig zijn — gevonden: $output",
        )
        assertTrue(
            output.contains("errorId"),
            "errorId-correlatie moet aanwezig zijn — gevonden: $output",
        )
    }

    @Test
    fun `5xx-log bevat GEEN exception-message (PII-protectie consistent met 4xx)`() {
        // Round 11 L1: 5xx-tak laat nu OOK exception.message weg uit de
        // log-regel. Stack-trace blijft via 1e arg. Cause-type wordt 2e
        // correlatie-handvat naast errorId.
        val piiMessage = "Upstream-fout voor Jan de Vries en BSN 999993653"

        mapper.toResponse(InternalServerErrorException(piiMessage))

        val errorRecords = records.filter { it.level == Level.SEVERE }
        assertTrue(errorRecords.isNotEmpty(), "verwacht 1 error-regel voor 5xx")
        // errorf formatteert eager — rec.message is al-gerenderd.
        val output = errorRecords.joinToString("\n") { it.message }
        assertFalse(
            output.contains(piiMessage),
            "PII-message mag NIET in 5xx-log staan — gevonden: $output",
        )
        assertFalse(
            output.contains("Jan de Vries"),
            "naam-PII mag niet in 5xx-log — gevonden: $output",
        )
        assertFalse(output.contains("999993653"), "BSN mag niet in 5xx-log — gevonden: $output")
        assertTrue(
            output.contains("InternalServerErrorException"),
            "type-correlatie moet aanwezig zijn — gevonden: $output",
        )
    }

    @Test
    fun `4xx-log format-spec correct met 4 args (errorId, type, cause)`() {
        // Round 11 M1: pin het exacte log-format zodat een toekomstige `{4}`-
        // uitbreiding zichtbaar wordt in diff-review. Aggregator-parsers die
        // op positionele velden ankerden zien een format-wijziging.
        mapper.toResponse(BadRequestException("ongeldig"))

        val rec = records.filter { it.level == Level.INFO }.first()
        assertEquals(
            "Client error {0} (errorId={1}, type={2}, cause={3})",
            rec.message,
            "Format-spec wijziging vereist update van log-aggregator-config",
        )
        assertEquals(4, rec.parameters!!.size, "Format-spec verwacht 4 args")
    }

    @Test
    fun `5xx-log format pin met cause-veld zichtbaar`() {
        // Pin 5xx-format-shape na Round 11 L1: errorf gebruikt printf en
        // formatteert eager — `rec.message` bevat al-gerenderd resultaat.
        // Borgt dat het cause-veld in de log-output staat (refactor die
        // het weghaalt zou aggregator-parsers breken).
        mapper.toResponse(InternalServerErrorException("test"))

        val rec = records.filter { it.level == Level.SEVERE }.first()
        assertTrue(
            rec.message.contains("Server error 500"),
            "shape moet status bevatten — gevonden: ${rec.message}",
        )
        assertTrue(
            rec.message.contains("type=InternalServerErrorException"),
            "type-veld vereist — gevonden: ${rec.message}",
        )
        assertTrue(
            rec.message.contains("cause="),
            "cause-veld vereist — gevonden: ${rec.message}",
        )
    }

    @Test
    fun `5xx-log format pin exact veld-volgorde (symmetrisch met 4xx)`() {
        // Round 12 M4: maakt 5xx-pin symmetrisch met 4xx-pin (regel 167-172).
        // 4xx kan exact `rec.message` matchen (MessageFormat is lazy).
        // 5xx printf eager-formatteert; we kunnen geen template terug-halen.
        // Wel kunnen we de exacte volgorde + scheidings-tokens pinnen via
        // regex die label= waarde paren in vaste volgorde verwacht.
        // Refactor die `cause`/`type` herordent of weghaalt slaat aggregator-
        // queries kapot die op deze positionele anker-volgorde zoeken.
        mapper.toResponse(InternalServerErrorException("test"))

        val rec = records.filter { it.level == Level.SEVERE }.first()
        val patroon = Regex(
            """^Server error 500 \(errorId=[0-9a-f-]+, type=InternalServerErrorException, cause=[A-Za-z]+\)$""",
        )
        assertTrue(
            patroon.matches(rec.message),
            "5xx-format-shape geschonden — verwacht 'Server error <status> (errorId=<uuid>, type=<class>, cause=<class>)' — gevonden: ${rec.message}",
        )
        assertNotNull(
            rec.thrown,
            "throwable moet in LogRecord.thrown — anders is stack-trace verloren in pipelines die op `thrown` filteren",
        )
    }

    @Test
    fun `4xx-instance is identiek aan errorId in de logregel (support-correlatie)`() {
        // Borgt dat de `urn:uuid:<id>` die de client als Problem.instance terugkrijgt
        // exact dezelfde correlatie-id is als die in de server-logregel staat. Een
        // refactor die voor log en response losse UUID's genereert breekt support-
        // tracering en wordt door deze test gevangen.
        val response = mapper.toResponse(BadRequestException("ongeldig"))

        val problem = response.entity as Problem
        val rec = records.first { it.level == Level.INFO }
        // `infov` levert positionele params: {1} is de errorId.
        val errorIdUitLog = rec.parameters!![1].toString()
        assertEquals("urn:uuid:$errorIdUitLog", problem.instance!!.toString())
    }

    @Test
    fun `5xx-instance is identiek aan errorId in log en detail (support-correlatie)`() {
        val response = mapper.toResponse(InternalServerErrorException("interne fout"))

        val problem = response.entity as Problem
        val rec = records.first { it.level == Level.SEVERE }
        val errorIdUitLog = Regex("errorId=([0-9a-f-]+)").find(rec.message)!!.groupValues[1]
        // Bij 5xx is het detail bewust gemaskeerd; de correlatie loopt via `instance`
        // (urn:uuid) en dezelfde errorId in de serverlog.
        assertEquals("urn:uuid:$errorIdUitLog", problem.instance!!.toString())
    }
}
