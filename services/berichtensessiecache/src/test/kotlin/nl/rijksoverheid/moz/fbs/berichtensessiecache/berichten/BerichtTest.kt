package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class BerichtTest {

    private val geldigBericht = Bericht(
        berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        afzender = "00000001234567890000",
        ontvanger = "999993653",
        onderwerp = "Test bericht",
        inhoud = "Inhoud van het bericht",
        publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
        magazijnId = "magazijn-a",
        aantalBijlagen = 0,
    )

    @Test
    fun `lege afzender wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            geldigBericht.copy(afzender = "")
        }
        assertEquals("afzender mag niet leeg zijn", ex.message)
    }

    @Test
    fun `blanco afzender wordt geweigerd`() {
        assertThrows<IllegalArgumentException> {
            geldigBericht.copy(afzender = "   ")
        }
    }

    @Test
    fun `lege ontvanger wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            geldigBericht.copy(ontvanger = "")
        }
        assertEquals("ontvanger mag niet leeg zijn", ex.message)
    }

    @Test
    fun `lege onderwerp wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            geldigBericht.copy(onderwerp = "")
        }
        assertEquals("onderwerp mag niet leeg zijn", ex.message)
    }

    @Test
    fun `lege magazijnId wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            geldigBericht.copy(magazijnId = "")
        }
        assertEquals("magazijnId mag niet leeg zijn", ex.message)
    }

    @Test
    fun `negatief aantalBijlagen wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            geldigBericht.copy(aantalBijlagen = -1)
        }
        assertEquals("aantalBijlagen mag niet negatief zijn", ex.message)
    }

    @Test
    fun `lege inhoud is toegestaan voor backwards-compat met oude cache-entries`() {
        // Bewuste keuze: domein staat lege inhoud toe (oude hash-entries hebben geen
        // veld; OpenAPI-spec dwingt het wel af op de wire). Hier vooral als regressie-
        // vangnet zodat de fallback in hashToBericht (`fields["inhoud"] ?: ""`) niet
        // alsnog een require-fout krijgt.
        val bericht = geldigBericht.copy(inhoud = "")
        assertEquals("", bericht.inhoud)
    }

    @Test
    fun `te veel bijlagen wordt geweigerd`() {
        val tooMany = (1..Bericht.MAX_BIJLAGEN + 1).map {
            BijlageSamenvatting(UUID.randomUUID(), "bijlage-$it.pdf")
        }

        val ex = assertThrows<IllegalArgumentException> {
            geldigBericht.copy(bijlagen = tooMany)
        }
        assertEquals("Maximaal ${Bericht.MAX_BIJLAGEN} bijlagen per bericht", ex.message)
    }

    @Test
    fun `te lange map wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            geldigBericht.copy(map = "x".repeat(Bericht.MAP_MAX_LENGTE + 1))
        }
        assertEquals("map-naam moet 1..${Bericht.MAP_MAX_LENGTE} tekens zijn", ex.message)
    }

    @Test
    fun `lege map wordt geweigerd`() {
        assertThrows<IllegalArgumentException> {
            geldigBericht.copy(map = "")
        }
    }

    @Test
    fun `null map is toegestaan`() {
        val bericht = geldigBericht.copy(map = null)
        assertEquals(null, bericht.map)
    }

    @Test
    fun `BijlageSamenvatting weigert lege naam`() {
        assertThrows<IllegalArgumentException> {
            BijlageSamenvatting(UUID.randomUUID(), "")
        }
    }

    @Test
    fun `BijlageSamenvatting weigert te lange naam`() {
        assertThrows<IllegalArgumentException> {
            BijlageSamenvatting(UUID.randomUUID(), "x".repeat(BijlageSamenvatting.NAAM_MAX_LENGTE + 1))
        }
    }
}
