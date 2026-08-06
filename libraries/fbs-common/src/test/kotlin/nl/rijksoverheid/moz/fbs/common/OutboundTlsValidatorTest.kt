package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

    @ParameterizedTest
    @ValueSource(
        strings = [
            "jdbc:postgresql://db:5432/ldv?ssl=true",
            "jdbc:postgresql://db:5432/ldv?sslmode=require",
            "jdbc:postgresql://db:5432/ldv?sslmode=verify-ca",
            "jdbc:postgresql://db:5432/ldv?sslmode=verify-full",
            "jdbc:postgresql://db:5432/ldv?user=x&sslmode=require&y=1",
        ],
    )
    fun `prod accepteert een JDBC-URL die daadwerkelijk versleutelt`(url: String) {
        assertDoesNotThrow { OutboundTlsValidator.requireJdbcTls("prod", url, "ldv.url") }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "jdbc:postgresql://db:5432/ldv",
            "jdbc:postgresql://db:5432/ldv?sslmode=disable",
            "jdbc:postgresql://db:5432/ldv?sslmode=allow",
            "jdbc:postgresql://db:5432/ldv?sslmode=prefer",
            "jdbc:postgresql://db:5432/ldv?ssl=false",
            "",
        ],
    )
    fun `prod weigert een JDBC-URL zonder gegarandeerde versleuteling`(url: String) {
        val ex = assertThrows<IllegalArgumentException> {
            OutboundTlsValidator.requireJdbcTls("prod", url, "ldv.url")
        }
        assertTrue(ex.message!!.contains("BIO 13.2.1"), "foutmelding moet naar BIO 13.2.1 verwijzen")
        assertTrue(ex.message!!.contains("ldv.url"), "foutmelding moet de configkey noemen")
    }

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `dev en test laten een plaintext JDBC-URL toe`(profiel: String) {
        assertDoesNotThrow {
            OutboundTlsValidator.requireJdbcTls(profiel, "jdbc:postgresql://localhost:5432/ldv", "ldv.url")
        }
    }

    @Test
    fun `de onveilige override laat plaintext JDBC toe in prod`() {
        assertDoesNotThrow {
            OutboundTlsValidator.requireJdbcTls(
                "prod",
                "jdbc:postgresql://db:5432/ldv",
                "ldv.url",
                unsafeAllowPlaintext = true,
            )
        }
    }

    @Test
    fun `de onveilige override logt luid met het stabiele alert-token voor JDBC`() {
        // Zelfde borging als requireHttps: zonder deze WARNING (met het greppable token)
        // blijft de ops-alertregel stil bij een bewust onveilig JDBC-endpoint.
        val warnings = warnRecords {
            OutboundTlsValidator.requireJdbcTls(
                "prod",
                "jdbc:postgresql://db:5432/ldv",
                "ldv.url",
                unsafeAllowPlaintext = true,
            )
        }

        val warning = warnings.singleOrNull()

        assertNotNull(warning, "plaintext-override MOET precies één WARNING loggen")
        assertTrue(warning!!.message.contains(OutboundTlsValidator.TLS_DISABLED_ALERT_TOKEN), "log moet het alert-token bevatten")
        assertTrue(warning.message.contains("ldv.url"), "log moet de config-key noemen voor diagnose")
    }

    @Test
    fun `de onveilige override is in dev een no-op zonder waarschuwing voor JDBC`() {
        // dev/test keren terug vóór de flag wordt geraadpleegd: geen throw én geen WARNING.
        val warnings = warnRecords {
            OutboundTlsValidator.requireJdbcTls(
                "dev",
                "jdbc:postgresql://localhost:5432/ldv",
                "ldv.url",
                unsafeAllowPlaintext = true,
            )
        }

        assertTrue(warnings.isEmpty(), "in dev mag de override niets loggen")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "jdbc:postgresql://db:5432/ldv?sslmode=require&sslmode=disable",
            "jdbc:postgresql://db:5432/ldv?ssl=true&ssl=false",
        ],
    )
    fun `bij een dubbele sleutel geldt het laatste voorkomen, ook als dat onveilig is`(url: String) {
        // pgJDBC zet de querystring sequentieel in een Properties-object: het laatste
        // voorkomen van een sleutel wint. De guard moet dus hetzelfde beoordelen als
        // waarmee de driver daadwerkelijk verbindt, niet "veilig als er ergens een veilige
        // waarde staat" — anders keurt hij een verbinding goed die in werkelijkheid
        // plaintext is.
        val ex = assertThrows<IllegalArgumentException> {
            OutboundTlsValidator.requireJdbcTls("prod", url, "ldv.url")
        }
        assertTrue(ex.message!!.contains("BIO 13.2.1"), "foutmelding moet naar BIO 13.2.1 verwijzen")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "jdbc:postgresql://db:5432/ldv?sslmode=disable&sslmode=require",
            "jdbc:postgresql://db:5432/ldv?ssl=false&ssl=true",
            "jdbc:postgresql://db:5432/ldv?user=x&sslmode=disable&sslmode=require",
        ],
    )
    fun `bij een dubbele sleutel geldt het laatste voorkomen, ook als dat veilig is`(url: String) {
        // De derde waarde combineert de dubbele sleutel met een onverwante parameter
        // (`user`), zodat alleen `ssl`/`sslmode` meetellen voor de beslissing.
        assertDoesNotThrow { OutboundTlsValidator.requireJdbcTls("prod", url, "ldv.url") }
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
