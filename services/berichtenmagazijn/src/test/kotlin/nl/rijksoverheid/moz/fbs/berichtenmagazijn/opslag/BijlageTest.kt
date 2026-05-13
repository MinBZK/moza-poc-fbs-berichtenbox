package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class BijlageTest {

    private fun bijlage(
        naam: String = "test.pdf",
        mimeType: String = "application/pdf",
        content: ByteArray = byteArrayOf(0x25, 0x50, 0x44, 0x46),
    ) = Bijlage(
        bijlageId = UUID.randomUUID(),
        berichtId = UUID.randomUUID(),
        naam = naam,
        mimeType = mimeType,
        content = content,
    )

    @Test
    fun `geldige bijlage`() {
        val b = bijlage()
        assertEquals("test.pdf", b.naam)
    }

    @Test
    fun `lege naam wordt geweigerd`() {
        assertThrows<DomainValidationException> { bijlage(naam = "") }
    }

    @Test
    fun `lege mimeType wordt geweigerd`() {
        assertThrows<DomainValidationException> { bijlage(mimeType = "") }
    }

    @Test
    fun `lege content wordt geweigerd`() {
        assertThrows<DomainValidationException> { bijlage(content = ByteArray(0)) }
    }

    @Test
    fun `te grote content wordt geweigerd`() {
        assertThrows<DomainValidationException> {
            bijlage(content = ByteArray(Bijlage.MAX_CONTENT_BYTES + 1))
        }
    }

    @Test
    fun `equals vergelijkt content op inhoud, niet op referentie`() {
        val a = bijlage(content = byteArrayOf(1, 2, 3))
        val b = a.copy(content = byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        val c = a.copy(content = byteArrayOf(1, 2, 4))
        assertNotEquals(a, c)
    }
}
