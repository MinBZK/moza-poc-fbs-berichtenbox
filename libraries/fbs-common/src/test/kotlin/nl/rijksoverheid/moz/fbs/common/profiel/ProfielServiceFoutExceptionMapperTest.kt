package nl.rijksoverheid.moz.fbs.common.profiel

import nl.rijksoverheid.moz.fbs.common.exception.Problem
import nl.rijksoverheid.moz.fbs.common.exception.ProblemMediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.UUID

class ProfielServiceFoutExceptionMapperTest {

    private val mapper = ProfielServiceFoutExceptionMapper()

    @Test
    fun `timeout-fault levert 503 met Retry-After 30`() {
        val response = mapper.toResponse(ProfielServiceFoutException.timeout())

        assertEquals(503, response.status)
        assertEquals("30", response.getHeaderString("Retry-After"))
    }

    @Test
    fun `mediatype is application problem+json`() {
        val response = mapper.toResponse(ProfielServiceFoutException.netwerk())

        assertEquals(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE, response.mediaType)
    }

    @Test
    fun `body is Problem met correcte velden`() {
        val response = mapper.toResponse(ProfielServiceFoutException.upstreamError(500))
        val problem = response.entity as Problem

        assertEquals(503, problem.status)
        assertEquals("Profiel-service tijdelijk niet beschikbaar", problem.title)
        assertEquals(URI.create("https://moza.nl/problems/profiel-service-onbereikbaar"), problem.type)
        assertNotNull(problem.detail)
        assertTrue(problem.detail!!.contains("30 seconden"), "detail moet retry-window vermelden: ${problem.detail}")
    }

    @Test
    fun `instance is urn uuid voor correlatie naar log`() {
        val response = mapper.toResponse(ProfielServiceFoutException.malformed())
        val problem = response.entity as Problem

        assertNotNull(problem.instance)
        assertTrue(
            problem.instance!!.toString().startsWith("urn:uuid:"),
            "instance moet urn:uuid:<id> zijn: ${problem.instance}",
        )
    }

    @Test
    fun `body bevat niet de oorspronkelijke exception-message (geen lek)`() {
        // upstreamError-message bevat statuscode; mag wel in log staan maar niet in Problem.detail.
        val response = mapper.toResponse(ProfielServiceFoutException.upstreamError(503))
        val problem = response.entity as Problem

        assertTrue(
            !problem.detail!!.contains("503") && !problem.detail.contains("HTTP"),
            "detail mag geen upstream-statuscode lekken: ${problem.detail}",
        )
    }

    @Test
    fun `onverwacht-fault levert ook 503 met Retry-After`() {
        // Catch-all-pad (NPE etc): client krijgt dezelfde 503 om interne details niet te lekken.
        val response = mapper.toResponse(ProfielServiceFoutException.onverwacht(IllegalStateException("interne bug")))

        assertEquals(503, response.status)
        assertEquals("30", response.getHeaderString("Retry-After"))
    }

    @Test
    fun `resolverMislukt-fault levert 503 met Retry-After`() {
        val response = mapper.toResponse(ProfielServiceFoutException.resolverMislukt(RuntimeException("await timeout")))

        assertEquals(503, response.status)
        assertEquals("30", response.getHeaderString("Retry-After"))
    }

    @Test
    fun `Problem-instance hergebruikt errorId uit de exception (geen nieuwe generatie)`() {
        // Mapper-randomUUID() zou cross-log-correlatie service ↔ client breken.
        val exception = ProfielServiceFoutException.timeout()
        val response = mapper.toResponse(exception)
        val problem = response.entity as Problem

        assertEquals(URI.create("urn:uuid:${exception.errorId}"), problem.instance)
    }

    @Test
    fun `CONFIG_DRIFT log heeft geen thrown - stacktrace blijft uit log voor PII-defense`() {
        // De KDoc-invariant op de factory borgt "geen cause"; deze test pint vast dat het
        // log-record GEEN thrown heeft — anders zou een toekomstige refactor
        // `log.errorf(exception, ...)` kunnen herinvoeren zonder dat een test stuk gaat.
        // De mapper gooit bewust niet bij een onverwachte cause (een throw in een
        // ExceptionMapper valt terug op het default-500-pad mét stacktrace).
        val targetLogger = java.util.logging.Logger.getLogger(ProfielServiceFoutExceptionMapper::class.java.name)
        val captured = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { captured.add(record) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val previousLevel = targetLogger.level

        targetLogger.addHandler(handler)
        targetLogger.level = java.util.logging.Level.ALL

        try {
            mapper.toResponse(ProfielServiceFoutException.configDrift())

            val configDriftLog = captured.firstOrNull { it.message?.contains("Config-drift") == true }

            org.junit.jupiter.api.Assertions.assertNotNull(
                configDriftLog,
                "Mapper moet CONFIG_DRIFT log-regel produceren",
            )
            org.junit.jupiter.api.Assertions.assertNull(
                configDriftLog!!.thrown,
                "CONFIG_DRIFT log-record mag GEEN thrown bevatten (anders stacktrace-PII-leak via upstream-URL)",
            )
        } finally {
            targetLogger.removeHandler(handler)
            targetLogger.level = previousLevel
        }
    }

    @Test
    fun `configDrift-factory garandeert geen cause (PII-invariant - upstream-URL met BSN mag niet lekken via stacktrace)`() {
        // KDoc op configDrift() expliciteert deze invariant; test pint hem vast zodat
        // een refactor die cause toevoegt direct stuk gaat. Defense-in-depth voor de
        // mapper-tak die anders alsnog log.errorf(exception, ...) zou kunnen krijgen.
        val exception = ProfielServiceFoutException.configDrift()

        org.junit.jupiter.api.Assertions.assertNull(
            exception.cause,
            "configDrift() mag geen cause hebben — anders kan upstream-URL met BSN/RSIN in pad via stacktrace lekken",
        )
    }

    @Test
    fun `CONFIG_DRIFT met onverwachte cause logt alert-marker zonder thrown of PII en levert toch veilige 500`() {
        // De cause==null-invariant op configDrift() maakt deze tak onbereikbaar via de public
        // factory; daarom construeren we hier bewust een CONFIG_DRIFT mét cause via de private
        // constructor om de defense-in-depth-tak te pinnen. Zou een toekomstige refactor
        // `log.errorf(exception, ...)` herinvoeren, dan rendert de stacktrace de cause-message —
        // die hier de upstream-URL met BSN simuleert — en breekt deze test.
        val ctor = ProfielServiceFoutException::class.java.getDeclaredConstructor(
            ProfielServiceFoutException.Categorie::class.java,
            Int::class.javaObjectType,
            UUID::class.java,
            String::class.java,
            Throwable::class.java,
        )

        ctor.isAccessible = true

        val piiInCause = "http://profiel-service/api/v1/partij/BSN/999993653"
        val exceptionMetCause = ctor.newInstance(
            ProfielServiceFoutException.Categorie.CONFIG_DRIFT,
            null,
            UUID.randomUUID(),
            "Configuratie-mismatch",
            IllegalStateException(piiInCause),
        ) as ProfielServiceFoutException

        val targetLogger = java.util.logging.Logger.getLogger(ProfielServiceFoutExceptionMapper::class.java.name)
        val captured = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { captured.add(record) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val previousLevel = targetLogger.level

        targetLogger.addHandler(handler)
        targetLogger.level = java.util.logging.Level.ALL

        try {
            val response = mapper.toResponse(exceptionMetCause)

            assertEquals(500, response.status)

            val invariantLog = captured.firstOrNull { it.message?.contains("config_drift_invariant") == true }

            assertNotNull(invariantLog, "Cause-tak moet een alert-gemarkeerde log-regel produceren")
            org.junit.jupiter.api.Assertions.assertNull(
                invariantLog!!.thrown,
                "Log-record mag GEEN thrown bevatten — anders lekt de cause-stacktrace de upstream-URL met BSN",
            )
            assertTrue(
                !invariantLog.message!!.contains("999993653") && !invariantLog.message!!.contains("profiel-service/api"),
                "Log mag de cause-message (upstream-URL met BSN) niet renderen: ${invariantLog.message}",
            )
        } finally {
            targetLogger.removeHandler(handler)
            targetLogger.level = previousLevel
        }
    }

    @Test
    fun `CONFIG_DRIFT levert 500 met configuratie-problem-type en geen Retry-After`() {
        // Config-drift is geen Profiel-storing; retry over 30s lost niets op. Mapper
        // moet eigen problem-type + 500 geven zodat client niet onnodig retry'd.
        val response = mapper.toResponse(ProfielServiceFoutException.configDrift())
        val problem = response.entity as Problem

        assertEquals(500, response.status)
        assertEquals(URI.create("https://moza.nl/problems/configuratie-mismatch"), problem.type)
        org.junit.jupiter.api.Assertions.assertNull(
            response.getHeaderString("Retry-After"),
            "CONFIG_DRIFT mag geen Retry-After hebben (retry helpt niet)",
        )
        assertTrue(
            problem.detail!!.contains("Retry heeft geen effect"),
            "Detail moet retry-zinloosheid noemen: ${problem.detail}",
        )
    }
}
