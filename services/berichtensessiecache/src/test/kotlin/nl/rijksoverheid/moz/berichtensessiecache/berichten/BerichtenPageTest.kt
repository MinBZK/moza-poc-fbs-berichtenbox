package nl.rijksoverheid.moz.berichtensessiecache.berichten

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BerichtenPageTest {

    @Test
    fun `negatieve page wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            BerichtenPage(emptyList(), page = -1, pageSize = 20, totalElements = 0, totalPages = 0)
        }
        assertEquals("page mag niet negatief zijn", ex.message)
    }

    @Test
    fun `pageSize nul wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            BerichtenPage(emptyList(), page = 0, pageSize = 0, totalElements = 0, totalPages = 0)
        }
        assertEquals("pageSize moet positief zijn", ex.message)
    }

    @Test
    fun `negatieve pageSize wordt geweigerd`() {
        assertThrows<IllegalArgumentException> {
            BerichtenPage(emptyList(), page = 0, pageSize = -5, totalElements = 0, totalPages = 0)
        }
    }

    @Test
    fun `negatieve totalElements wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            BerichtenPage(emptyList(), page = 0, pageSize = 20, totalElements = -1, totalPages = 0)
        }
        assertEquals("totalElements mag niet negatief zijn", ex.message)
    }

    @Test
    fun `negatieve totalPages wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            BerichtenPage(emptyList(), page = 0, pageSize = 20, totalElements = 0, totalPages = -1)
        }
        assertEquals("totalPages mag niet negatief zijn", ex.message)
    }

    @Test
    fun `geldige lege pagina`() {
        val page = BerichtenPage(emptyList(), page = 0, pageSize = 20, totalElements = 0, totalPages = 0)
        assertEquals(0, page.berichten.size)
    }
}
