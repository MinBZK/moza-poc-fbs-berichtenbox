package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

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
}
