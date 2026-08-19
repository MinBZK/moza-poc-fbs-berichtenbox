package nl.rijksoverheid.moz.fbs.common.fsc

import io.smallrye.config.SmallRyeConfigBuilder
import org.eclipse.microprofile.config.Config
import org.junit.jupiter.api.Assertions.assertFalse
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

class OutwayTlsValidatorTest {

    private fun config(vararg paren: Pair<String, String>): Config =
        SmallRyeConfigBuilder().withDefaultValues(paren.toMap()).build()

    private fun valideer(
        profile: String = "prod",
        vararg paren: Pair<String, String>,
        unsafe: Boolean = false,
    ) = OutwayTlsValidator.valideer(profile, config(*paren), unsafe)

    @Test
    fun `zonder outway-configuratie blijft het bij de JVM-default trust-store`() {
        assertDoesNotThrow { valideer() }
    }

    @Test
    fun `een geldig anchor wordt geaccepteerd`() {
        assertDoesNotThrow {
            valideer(paren = arrayOf("quarkus.tls.outway.trust-store.pem.certs" to "/etc/ca.pem"))
        }
    }

    /**
     * De diagnose bij een typefout: de operator moet zijn eigen spelling terugzien naast de naam
     * die de applicatie zoekt. Zonder die opsomming leest "geen anchor" als "niets geconfigureerd",
     * terwijl er een volledig gevalideerde bucket onder een andere naam staat.
     */
    @Test
    fun `een bucket onder een andere naam wordt genoemd in de melding`() {
        val meldingen = vangLogs {
            valideer(paren = arrayOf("quarkus.tls.out-way.trust-store.pem.certs" to "/etc/ca.pem"))
        }

        assertTrue(meldingen.any { it.contains("out-way") }, "verwacht de gevonden bucketnaam, kreeg: $meldingen")
        assertFalse(meldingen.any { it.contains("geen enkele quarkus.tls-configuratie") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["quarkus.tls.outway.trust-all", "quarkus.tls.trust-all"])
    fun `trust-all op het anchor of op de default wordt geweigerd`(sleutel: String) {
        val fout = assertThrows<IllegalStateException> {
            valideer(paren = arrayOf("quarkus.tls.outway.trust-store.pem.certs" to "/etc/ca.pem", sleutel to "true"))
        }

        assertTrue(fout.message!!.contains("trust-all"), fout.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NONE", "none"])
    fun `hostnaam-verificatie uit wordt geweigerd`(waarde: String) {
        val fout = assertThrows<IllegalStateException> {
            valideer(
                paren = arrayOf(
                    "quarkus.tls.outway.trust-store.pem.certs" to "/etc/ca.pem",
                    "quarkus.tls.outway.hostname-verification-algorithm" to waarde,
                ),
            )
        }

        assertTrue(fout.message!!.contains("hostname-verification-algorithm"), fout.message)
    }

    @Test
    fun `HTTPS als verificatie-algoritme is gewoon goed`() {
        assertDoesNotThrow {
            valideer(
                paren = arrayOf(
                    "quarkus.tls.outway.trust-store.pem.certs" to "/etc/ca.pem",
                    "quarkus.tls.outway.hostname-verification-algorithm" to "HTTPS",
                ),
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `dev en test mogen een ongeverifieerd anchor houden`(profile: String) {
        assertDoesNotThrow {
            valideer(
                profile = profile,
                paren = arrayOf(
                    "quarkus.tls.outway.trust-store.pem.certs" to "/etc/ca.pem",
                    "quarkus.tls.outway.trust-all" to "true",
                ),
            )
        }
    }

    @Test
    fun `de onveilige klep staat het toe en logt luid met het stabiele token`() {
        val meldingen = vangLogs {
            valideer(
                paren = arrayOf(
                    "quarkus.tls.outway.trust-store.pem.certs" to "/etc/ca.pem",
                    "quarkus.tls.outway.trust-all" to "true",
                ),
                unsafe = true,
            )
        }

        assertTrue(
            meldingen.any { it.contains(OutwayTlsValidator.ONGEVERIFIEERD_ALERT_TOKEN) },
            "verwacht het alert-token, kreeg: $meldingen",
        )
    }

    /**
     * Het token is het aanknopingspunt van een alert-regel bij ops; wijzigen zonder die regel mee
     * te verhuizen laat de detectie stilvallen.
     */
    @Test
    fun `het alert-token ligt vast`() {
        org.junit.jupiter.api.Assertions.assertEquals(
            "OUTWAY_TLS_UNVERIFIED",
            OutwayTlsValidator.ONGEVERIFIEERD_ALERT_TOKEN,
        )
    }

    private fun vangLogs(blok: () -> Unit): List<String> {
        val logger = Logger.getLogger(OutwayTlsValidator::class.java.name)
        val opgevangen = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                opgevangen += record.message
            }

            override fun flush() = Unit
            override fun close() = Unit
        }

        logger.addHandler(handler)
        logger.level = Level.ALL

        try {
            blok()
        } finally {
            logger.removeHandler(handler)
        }

        return opgevangen
    }
}
