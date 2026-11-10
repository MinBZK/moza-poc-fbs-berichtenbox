package nl.rijksoverheid.moz.fbs.democonsole.sessie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SessieverwijzerTest {

    private val verwijzer = Sessieverwijzer()

    @Test
    fun `dezelfde sessie krijgt steeds dezelfde verwijzing`() {
        // Anders verandert het antwoord bij elke uitlezing en tekent het paneel de lijst elke vijf
        // seconden opnieuw.
        assertEquals(verwijzer.voor(SLEUTEL, HASH), verwijzer.voor(SLEUTEL, HASH))
    }

    @Test
    fun `een andere sessie of een andere ontvanger krijgt een andere verwijzing`() {
        val basis = verwijzer.voor(SLEUTEL, HASH)

        assertNotEquals(basis, verwijzer.voor(SLEUTEL, "f".repeat(64)))
        assertNotEquals(basis, verwijzer.voor(SLEUTEL.replace(":v4:", ":v5:"), HASH))
    }

    @Test
    fun `een andere console geeft dezelfde sessie een andere verwijzing`() {
        assertNotEquals(verwijzer.voor(SLEUTEL, HASH), Sessieverwijzer().voor(SLEUTEL, HASH))
    }

    @Test
    fun `de verwijzing heeft de vaste vorm en draagt niets van de hash`() {
        val verwijzing = verwijzer.voor(SLEUTEL, HASH)

        assertTrue(Sessieverwijzer.isGeldig(verwijzing), "'$verwijzing' heeft niet de vorm die de console accepteert")
        assertEquals(Sessieverwijzer.LENGTE, verwijzing.length)
        assertFalse(verwijzing.contains(HASH.take(6)), "de verwijzing bevat een stuk van de hash")
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = ["", "kort", "AAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAA+/", "AAAAAAAAAA AAAAAAAAAAA"])
    fun `een waarde van een andere vorm is geen verwijzing`(waarde: String) {
        assertFalse(Sessieverwijzer.isGeldig(waarde))
    }

    private companion object {

        const val SLEUTEL = "berichtensessiecache:v4:6a9056ae50ec26106e793b6cb9382d9ad2bcef88ae735fdc802c8206fd50dd3e:sessies"

        const val HASH = "9c1185a5c5e9fc54612808977ee8f548b2258d31d4c8a6b8e6d0b7d5d6a3c3f1"
    }
}
