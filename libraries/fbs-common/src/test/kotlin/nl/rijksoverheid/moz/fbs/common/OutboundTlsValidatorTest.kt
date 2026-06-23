package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class OutboundTlsValidatorTest {

    private val key = "some.outbound.endpoint"

    @Test
    fun `dev-profiel laat http-endpoint toe`() {
        assertDoesNotThrow {
            OutboundTlsValidator.requireHttps("dev", "http://localhost:8089", key)
        }
    }

    @Test
    fun `test-profiel laat http-endpoint toe`() {
        assertDoesNotThrow {
            OutboundTlsValidator.requireHttps("test", "http://localhost:8089", key)
        }
    }

    @Test
    fun `prod-profiel met http-endpoint faalt fail-fast`() {
        val ex = assertThrows<IllegalArgumentException> {
            OutboundTlsValidator.requireHttps("prod", "http://intern.endpoint", key)
        }
        assertTrue(ex.message!!.contains("BIO 13.2.1"))
        assertTrue(ex.message!!.contains("https://"))
        assertTrue(ex.message!!.contains(key), "foutmelding moet de config-key noemen voor diagnose")
        assertTrue(ex.message!!.contains("http://intern.endpoint"))
    }

    @Test
    fun `prod-profiel met https-endpoint slaagt`() {
        assertDoesNotThrow {
            OutboundTlsValidator.requireHttps("prod", "https://intern.endpoint", key)
        }
    }

    @Test
    fun `staging-profiel valt onder de TLS-eis`() {
        assertThrows<IllegalArgumentException> {
            OutboundTlsValidator.requireHttps("staging", "http://x", key)
        }
    }

    @Test
    fun `acceptatie-profiel valt onder de TLS-eis`() {
        assertThrows<IllegalArgumentException> {
            OutboundTlsValidator.requireHttps("acceptatie", "http://x", key)
        }
    }

    @Test
    fun `onbekend profiel (typo) valt onder de TLS-eis`() {
        // Een typo zoals 'productie' of 'Dev' (hoofdletter) mag de TLS-eis niet
        // silent bypassen — fail-closed.
        assertThrows<IllegalArgumentException> {
            OutboundTlsValidator.requireHttps("productie", "http://x", key)
        }
        assertThrows<IllegalArgumentException> {
            OutboundTlsValidator.requireHttps("Dev", "http://x", key)
        }
    }

    @Test
    fun `prod met http mag als unsafeAllowPlaintext aanstaat (bewust onveilig)`() {
        // Bewust onveilige override: plaintext toegestaan voor een intern endpoint.
        // Alleen verantwoord bij mesh-mTLS of zonder echte persoonsgegevens.
        assertDoesNotThrow {
            OutboundTlsValidator.requireHttps("prod", "http://clickhouse.intern:8123", key, unsafeAllowPlaintext = true)
        }
    }

    @Test
    fun `unsafeAllowPlaintext staat default uit, dus http in prod blijft falen`() {
        assertThrows<IllegalArgumentException> {
            OutboundTlsValidator.requireHttps("prod", "http://clickhouse.intern:8123", key)
        }
    }

    @Test
    fun `unsafeAllowPlaintext laat https ongemoeid`() {
        assertDoesNotThrow {
            OutboundTlsValidator.requireHttps("prod", "https://clickhouse.intern:8443", key, unsafeAllowPlaintext = true)
        }
    }

    @Test
    fun `unsafeAllowPlaintext logt luid met het stabiele alert-token`() {
        // De hele veiligheidsrechtvaardiging van de override is "bij gebruik wordt luid
        // gewaarschuwd" + een greppable token waar ops op alert. Borg dat de WARNING valt
        // en het token bevat; een refactor die de log laat vallen moet hier breken.
        val warnings = warnRecords {
            OutboundTlsValidator.requireHttps("prod", "http://clickhouse.intern:8123", key, unsafeAllowPlaintext = true)
        }

        val warning = warnings.singleOrNull()

        assertNotNull(warning, "plaintext-override MOET precies één WARNING loggen")
        assertTrue(warning!!.message.contains(OutboundTlsValidator.TLS_DISABLED_ALERT_TOKEN), "log moet het alert-token bevatten")
        assertTrue(warning.message.contains(key), "log moet de config-key noemen voor diagnose")
    }

    @Test
    fun `unsafeAllowPlaintext is in dev een no-op zonder waarschuwing`() {
        // dev/test keren terug vóór de flag wordt geraadpleegd: geen throw én geen WARNING,
        // anders zou lokaal draaien telkens valse plaintext-alarmen genereren.
        val warnings = warnRecords {
            OutboundTlsValidator.requireHttps("dev", "http://localhost:8123", key, unsafeAllowPlaintext = true)
        }

        assertTrue(warnings.isEmpty(), "in dev mag de override niets loggen")
    }

    @Test
    fun `unsafeAllowPlaintext laat een lege endpoint-waarde toe in prod`() {
        // Een lege waarde is geen https, maar de bewuste override onderdrukt de eis ook hier.
        assertDoesNotThrow {
            OutboundTlsValidator.requireHttps("prod", "", key, unsafeAllowPlaintext = true)
        }
    }

    /** Vangt de WARNING-records die [block] op de validator-logger produceert. */
    private fun warnRecords(block: () -> Unit): List<LogRecord> {
        val records = mutableListOf<LogRecord>()

        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records.add(record)
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

        val logger = Logger.getLogger(OutboundTlsValidator::class.java.name)
        logger.addHandler(handler)

        try {
            block()
        } finally {
            logger.removeHandler(handler)
        }

        return records.filter { it.level == Level.WARNING }
    }
}
