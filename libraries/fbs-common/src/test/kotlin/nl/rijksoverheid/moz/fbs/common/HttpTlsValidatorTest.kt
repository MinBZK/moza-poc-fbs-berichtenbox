package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class HttpTlsValidatorTest {

    @Test
    fun `dev mag plain http zonder keystore`() {
        assertDoesNotThrow {
            HttpTlsValidator.validate(
                profile = "dev",
                insecureRequests = "enabled",
                keyStoreFile = null,
                tlsTermination = "app",
            )
        }
    }

    @Test
    fun `test mag plain http zonder keystore`() {
        assertDoesNotThrow {
            HttpTlsValidator.validate(
                profile = "test",
                insecureRequests = "enabled",
                keyStoreFile = null,
                tlsTermination = "app",
            )
        }
    }

    @Test
    fun `prod met insecure-requests=enabled en geen keystore faalt fail-fast`() {
        val ex = assertThrows<IllegalArgumentException> {
            HttpTlsValidator.validate(
                profile = "prod",
                insecureRequests = "enabled",
                keyStoreFile = null,
                tlsTermination = "app",
            )
        }
        assertTrue(ex.message!!.contains("BIO 13.2.1"), "moet BIO 13.2.1 noemen")
    }

    @Test
    fun `prod met insecure-requests=disabled maar geen keystore faalt`() {
        val ex = assertThrows<IllegalArgumentException> {
            HttpTlsValidator.validate(
                profile = "prod",
                insecureRequests = "disabled",
                keyStoreFile = null,
                tlsTermination = "app",
            )
        }
        assertTrue(ex.message!!.contains("key-store-file"), "moet ontbrekende keystore noemen")
    }

    @Test
    fun `prod met insecure-requests=disabled en keystore slaagt`() {
        assertDoesNotThrow {
            HttpTlsValidator.validate(
                profile = "prod",
                insecureRequests = "disabled",
                keyStoreFile = "/etc/ssl/keystore.p12",
                tlsTermination = "app",
            )
        }
    }

    @Test
    fun `prod met mesh-terminatie slaagt zonder app-niveau keystore`() {
        assertDoesNotThrow {
            HttpTlsValidator.validate(
                profile = "prod",
                insecureRequests = "enabled",
                keyStoreFile = null,
                tlsTermination = "mesh",
            )
        }
    }

    @Test
    fun `ongeldige tls-terminatie waarde faalt`() {
        val ex = assertThrows<IllegalArgumentException> {
            HttpTlsValidator.validate(
                profile = "prod",
                insecureRequests = "disabled",
                keyStoreFile = "/x/keystore.p12",
                tlsTermination = "onzin",
            )
        }
        assertTrue(ex.message!!.contains("'app' of 'mesh'"), "foutmelding moet geldige waardes noemen")
    }

    @Test
    fun `acceptatie en staging vallen onder de TLS-eis`() {
        assertThrows<IllegalArgumentException> {
            HttpTlsValidator.validate("staging", "enabled", null, "app")
        }
        assertThrows<IllegalArgumentException> {
            HttpTlsValidator.validate("acceptatie", "enabled", null, "app")
        }
    }
}
