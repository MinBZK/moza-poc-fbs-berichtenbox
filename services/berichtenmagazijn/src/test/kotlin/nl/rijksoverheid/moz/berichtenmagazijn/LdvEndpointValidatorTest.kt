package nl.rijksoverheid.moz.berichtenmagazijn

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class LdvEndpointValidatorTest {

    @Test
    fun `dev-profiel laat http-endpoint toe`() {
        assertDoesNotThrow { LdvEndpointValidator.validate("dev", "http://localhost:8123") }
    }

    @Test
    fun `test-profiel laat http-endpoint toe`() {
        assertDoesNotThrow { LdvEndpointValidator.validate("test", "http://localhost:8123") }
    }

    @Test
    fun `prod-profiel met http-endpoint faalt fail-fast`() {
        val ex = assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate("prod", "http://insecure:8123")
        }
        assertTrue(ex.message!!.contains("BIO 13.2.1"), "foutmelding moet naar BIO 13.2.1 verwijzen")
        assertTrue(ex.message!!.contains("https://"), "foutmelding moet https:// noemen")
        assertTrue(
            ex.message!!.contains("http://insecure:8123"),
            "foutmelding moet de huidige (foutieve) waarde tonen voor diagnose",
        )
    }

    @Test
    fun `prod-profiel met https-endpoint slaagt`() {
        assertDoesNotThrow { LdvEndpointValidator.validate("prod", "https://clickhouse.intern:8443") }
    }

    @Test
    fun `niet-dev-test profielen vallen onder de TLS-eis`() {
        assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate("staging", "http://x:8123")
        }
        assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate("acceptatie", "http://x:8123")
        }
    }

    @Test
    fun `lege endpoint-waarde faalt buiten dev-test`() {
        assertThrows<IllegalArgumentException> { LdvEndpointValidator.validate("prod", "") }
    }
}
