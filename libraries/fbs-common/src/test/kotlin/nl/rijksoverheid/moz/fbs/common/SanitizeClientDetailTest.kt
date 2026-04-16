package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SanitizeClientDetailTest {

    @Test
    fun `null blijft null`() {
        assertNull(sanitizeClientDetail(null))
    }

    @Test
    fun `blank wordt null`() {
        assertNull(sanitizeClientDetail("   "))
    }

    @Test
    fun `eenvoudige NL boodschap overleeft`() {
        assertEquals(
            "Header 'X-Ontvanger' is verplicht",
            sanitizeClientDetail("Header 'X-Ontvanger' is verplicht"),
        )
    }

    @Test
    fun `stacktrace-frames worden verwijderd`() {
        val raw = "NullPointerException at nl.rijksoverheid.moz.Foo.bar(Foo.kt:42) op lijn"
        val sanitized = sanitizeClientDetail(raw) ?: ""
        assertFalse(sanitized.contains("at nl.rijksoverheid"), "stack frame moet weg: $sanitized")
        assertFalse(sanitized.contains("Foo.kt:42"), "file+line moet weg: $sanitized")
    }

    @Test
    fun `file-paden worden verwijderd`() {
        val unix = sanitizeClientDetail("Kan niet openen: /etc/secrets/db.yaml bestaat niet")
        val windows = sanitizeClientDetail("""Kan niet openen: C:\Users\app\secrets.txt""")
        assertFalse(unix!!.contains("/etc/secrets"), "unix-pad moet weg: $unix")
        assertFalse(windows!!.contains("""C:\Users"""), "windows-pad moet weg: $windows")
    }

    @Test
    fun `controltekens worden vervangen door spaties`() {
        val raw = "regel1\nregel2\ttab\u0000nul"
        val sanitized = sanitizeClientDetail(raw) ?: ""
        assertFalse(sanitized.contains("\n"), "newline moet weg")
        assertFalse(sanitized.contains("\t"), "tab moet weg")
        assertFalse(sanitized.contains("\u0000"), "null-byte moet weg")
    }

    @Test
    fun `lange boodschap wordt afgekapt op 500 tekens`() {
        val lang = "x".repeat(600)
        val sanitized = sanitizeClientDetail(lang) ?: ""
        assertEquals(500, sanitized.length)
    }
}
