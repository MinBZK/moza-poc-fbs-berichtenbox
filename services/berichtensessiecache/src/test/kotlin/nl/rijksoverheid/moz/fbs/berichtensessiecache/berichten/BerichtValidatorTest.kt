package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class BerichtValidatorTest {

    private val limieten = object : BerichtLimieten {
        override fun maxBijlagen() = 2
        override fun maxBijlageNaamLengte() = 10
    }

    private val validator = BerichtValidator(limieten)

    private val basisBericht = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = "00000001234567890000",
        ontvanger = "999993653",
        onderwerp = "Test",
        inhoud = "Inhoud",
        publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
        magazijnId = "magazijn-a",
        aantalBijlagen = 0,
    )

    @Test
    fun `valideer staat bericht binnen limieten toe`() {
        val bericht = basisBericht.copy(
            bijlagen = listOf(BijlageSamenvatting(UUID.randomUUID(), "kort.pdf")),
        )

        val resultaat = validator.valideer(bericht)

        assertEquals(bericht, resultaat)
    }

    @Test
    fun `valideer weigert bericht met te veel bijlagen`() {
        val teVeel = (1..3).map { BijlageSamenvatting(UUID.randomUUID(), "b$it.pdf") }
        val bericht = basisBericht.copy(bijlagen = teVeel)

        val ex = assertThrows<IllegalArgumentException> { validator.valideer(bericht) }
        assertEquals("Maximaal 2 bijlagen per bericht", ex.message)
    }

    @Test
    fun `valideer weigert bijlage met te lange naam`() {
        val teLangeNaam = "x".repeat(11)
        val bericht = basisBericht.copy(
            bijlagen = listOf(BijlageSamenvatting(UUID.randomUUID(), teLangeNaam)),
        )

        val ex = assertThrows<IllegalArgumentException> { validator.valideer(bericht) }
        assertEquals("bijlage-naam mag maximaal 10 tekens zijn", ex.message)
    }

    @Test
    fun `valideerOrLogAndDrop retourneert bericht binnen limieten`() {
        val bericht = basisBericht.copy(
            bijlagen = listOf(BijlageSamenvatting(UUID.randomUUID(), "kort.pdf")),
        )

        assertNotNull(validator.valideerOrLogAndDrop(bericht))
    }

    @Test
    fun `valideerOrLogAndDrop retourneert null bij te veel bijlagen`() {
        val teVeel = (1..3).map { BijlageSamenvatting(UUID.randomUUID(), "b$it.pdf") }
        val bericht = basisBericht.copy(bijlagen = teVeel)

        assertNull(validator.valideerOrLogAndDrop(bericht))
    }

    @Test
    fun `valideerOrLogAndDrop retourneert null bij te lange bijlage-naam`() {
        val bericht = basisBericht.copy(
            bijlagen = listOf(BijlageSamenvatting(UUID.randomUUID(), "x".repeat(11))),
        )

        assertNull(validator.valideerOrLogAndDrop(bericht))
    }

}
