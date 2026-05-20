package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PublicatiedoelTest {

    @Test
    fun `valide doel-keys worden geaccepteerd`() {
        Publicatiedoel("aanmeld")
        Publicatiedoel("notificatie")
        Publicatiedoel("aanmeld-service")
        Publicatiedoel("a")
        Publicatiedoel("ledenadministratie123")
    }

    @Test
    fun `lege key wordt geweigerd`() {
        assertThrows(DomainValidationException::class.java) { Publicatiedoel("") }
    }

    @Test
    fun `hoofdletters worden geweigerd`() {
        assertThrows(DomainValidationException::class.java) { Publicatiedoel("Aanmeld") }
    }

    @Test
    fun `key met punt of underscore wordt geweigerd`() {
        assertThrows(DomainValidationException::class.java) { Publicatiedoel("aan.meld") }
        assertThrows(DomainValidationException::class.java) { Publicatiedoel("aan_meld") }
    }

    @Test
    fun `key langer dan 64 wordt geweigerd vanwege DB-kolom-limiet`() {
        val tachtigA = "a".repeat(80)
        assertThrows(DomainValidationException::class.java) { Publicatiedoel(tachtigA) }
    }

    @Test
    fun `toString geeft de key terug`() {
        assertEquals("aanmeld", Publicatiedoel("aanmeld").toString())
    }
}
