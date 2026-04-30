package nl.rijksoverheid.moz.fbs.common.exception

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class SanitizeClientDetailTest {

    @Test
    fun `null blijft null`() {
        Assertions.assertNull(sanitizeClientDetail(null))
    }

    @Test
    fun `blank wordt null`() {
        Assertions.assertNull(sanitizeClientDetail("   "))
    }

    @Test
    fun `eenvoudige NL boodschap overleeft`() {
        Assertions.assertEquals(
            "Header 'X-Ontvanger' is verplicht",
            sanitizeClientDetail("Header 'X-Ontvanger' is verplicht"),
        )
    }

    @Test
    fun `stacktrace-frames worden verwijderd`() {
        val raw = "NullPointerException at nl.rijksoverheid.moz.Foo.bar(Foo.kt:42) op lijn"
        val sanitized = sanitizeClientDetail(raw) ?: ""
        Assertions.assertFalse(sanitized.contains("at nl.rijksoverheid"), "stack frame moet weg: $sanitized")
        Assertions.assertFalse(sanitized.contains("Foo.kt:42"), "file+line moet weg: $sanitized")
    }

    @Test
    fun `file-paden worden verwijderd`() {
        val unix = sanitizeClientDetail("Kan niet openen: /etc/secrets/db.yaml bestaat niet")
        val windows = sanitizeClientDetail("""Kan niet openen: C:\Users\app\secrets.txt""")
        Assertions.assertFalse(unix!!.contains("/etc/secrets"), "unix-pad moet weg: $unix")
        Assertions.assertFalse(windows!!.contains("""C:\Users"""), "windows-pad moet weg: $windows")
    }

    @Test
    fun `controltekens worden vervangen door spaties`() {
        val raw = "regel1\nregel2\ttab\u0000nul"
        val sanitized = sanitizeClientDetail(raw) ?: ""
        Assertions.assertFalse(sanitized.contains("\n"), "newline moet weg")
        Assertions.assertFalse(sanitized.contains("\t"), "tab moet weg")
        Assertions.assertFalse(sanitized.contains("\u0000"), "null-byte moet weg")
    }

    @Test
    fun `lange boodschap wordt afgekapt op 500 tekens`() {
        val lang = "x".repeat(600)
        val sanitized = sanitizeClientDetail(lang) ?: ""
        Assertions.assertEquals(500, sanitized.length)
    }
}
