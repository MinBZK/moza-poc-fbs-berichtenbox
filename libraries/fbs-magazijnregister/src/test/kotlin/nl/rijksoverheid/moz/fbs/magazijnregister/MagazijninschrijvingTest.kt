package nl.rijksoverheid.moz.fbs.magazijnregister

import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.net.URI

/**
 * Pint de type-eigen URL-guard: geen enkele producent (config-backed nu,
 * database-backed later) kan een inschrijving met een niet-http(s)- of
 * hostloze URL construeren. De profiel-afhankelijke TLS-eis wordt apart
 * getest via [ConfigMagazijnregisterTest].
 */
class MagazijninschrijvingTest {

    private val oin = Oin("00000001003214345000")

    @Test
    fun `http- en https-URLs met host zijn construeerbaar`() {
        assertDoesNotThrow { Magazijninschrijving(oin, URI.create("http://localhost:8081"), naam = null) }
        assertDoesNotThrow { Magazijninschrijving(oin, URI.create("https://magazijn.intern:8443"), naam = "Magazijn") }
    }

    @Test
    fun `niet-http-scheme is niet construeerbaar`() {
        val ex = assertThrows<IllegalArgumentException> {
            Magazijninschrijving(oin, URI.create("ftp://example.com"), naam = null)
        }

        assertTrue(ex.message!!.contains("http(s)"))
    }

    @Test
    fun `hostloze URL is niet construeerbaar`() {
        val ex = assertThrows<IllegalArgumentException> {
            Magazijninschrijving(oin, URI.create("http:///geen-host"), naam = null)
        }

        assertTrue(ex.message!!.contains("host"))
    }

    @Test
    fun `grantHash is optioneel en niet-blanco waarden zijn construeerbaar`() {
        assertDoesNotThrow {
            Magazijninschrijving(oin, URI.create("http://localhost:8081"), naam = null, grantHash = null)
        }
        assertDoesNotThrow {
            Magazijninschrijving(oin, URI.create("http://localhost:8081"), naam = null, grantHash = "abc123")
        }
    }

    @Test
    fun `lege grantHash is niet construeerbaar`() {
        val ex = assertThrows<IllegalArgumentException> {
            Magazijninschrijving(oin, URI.create("http://localhost:8081"), naam = null, grantHash = "")
        }

        assertTrue(ex.message!!.contains("grantHash"))
    }

    @Test
    fun `whitespace-only grantHash is niet construeerbaar`() {
        val ex = assertThrows<IllegalArgumentException> {
            Magazijninschrijving(oin, URI.create("http://localhost:8081"), naam = null, grantHash = "   ")
        }

        assertTrue(ex.message!!.contains("grantHash"))
    }
}
