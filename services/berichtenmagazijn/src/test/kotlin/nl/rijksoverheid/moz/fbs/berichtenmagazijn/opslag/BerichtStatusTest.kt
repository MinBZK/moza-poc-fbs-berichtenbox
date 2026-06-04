package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class BerichtStatusTest {

    @Test
    fun `geldige status met map`() {
        val s = BerichtStatus(gelezen = true, map = "archief", gewijzigdOp = Instant.now())
        assertEquals("archief", s.map)
    }

    @Test
    fun `null map is toegestaan`() {
        val s = BerichtStatus(gelezen = false, map = null, gewijzigdOp = Instant.now())
        assertEquals(null, s.map)
    }

    @Test
    fun `lege map (whitespace-only) wordt geweigerd`() {
        assertThrows<DomainValidationException> {
            BerichtStatus(gelezen = false, map = "   ", gewijzigdOp = Instant.now())
        }
    }

    @Test
    fun `te lange mapnaam wordt geweigerd`() {
        val tooLong = "x".repeat(BerichtStatus.MAX_MAPNAAM_LENGTE + 1)
        assertThrows<DomainValidationException> {
            BerichtStatus(gelezen = false, map = tooLong, gewijzigdOp = Instant.now())
        }
    }
}
