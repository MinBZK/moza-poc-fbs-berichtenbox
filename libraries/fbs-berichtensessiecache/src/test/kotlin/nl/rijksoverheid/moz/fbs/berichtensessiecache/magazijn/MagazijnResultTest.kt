package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockedDependenciesProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class MagazijnResultTest {

    private val bericht = Bericht(
        berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        afzender = "00000001234567890000",
        ontvanger = "999993653",
        onderwerp = "test",
        inhoud = "inhoud",
        publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
        magazijnId = "magazijn-a",
        aantalBijlagen = 0,
    )

    @Test
    fun `Success bevat magazijn-id naam en berichten`() {
        val success = MagazijnResult.Success("magazijn-a", "Magazijn A", listOf(bericht))

        assertEquals("magazijn-a", success.magazijnId)
        assertEquals("Magazijn A", success.naam)
        assertEquals(1, success.berichten.size)
    }

    @Test
    fun `Failure bevat magazijn-id naam error en fault`() {
        val ex = RuntimeException("test")
        val failure = MagazijnResult.Failure("magazijn-b", "Magazijn B", ex, MagazijnFault.INTERNAL_BUG)

        assertEquals("magazijn-b", failure.magazijnId)
        assertEquals("Magazijn B", failure.naam)
        assertEquals(ex, failure.error)
        assertEquals(MagazijnFault.INTERNAL_BUG, failure.fault)
    }

    @Test
    fun `data class equals en hashCode werken`() {
        val a1 = MagazijnResult.Success("id", "n", listOf(bericht))
        val a2 = MagazijnResult.Success("id", "n", listOf(bericht))
        val b = MagazijnResult.Success("id", "andere", listOf(bericht))

        assertEquals(a1, a2)
        assertEquals(a1.hashCode(), a2.hashCode())
        assertNotEquals(a1, b)
        assertNotNull(a1.toString())
    }

    @Test
    fun `Success en Failure zijn niet gelijk`() {
        val success: MagazijnResult = MagazijnResult.Success("id", "n", emptyList())
        val failure: MagazijnResult = MagazijnResult.Failure("id", "n", RuntimeException(), MagazijnFault.INTERNAL_BUG)

        assertNotEquals(success, failure)
    }

    @Test
    fun `Success met lege magazijn-id gooit IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            MagazijnResult.Success("", "Magazijn", listOf(bericht))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MagazijnResult.Success("   ", "Magazijn", listOf(bericht))
        }
    }

    @Test
    fun `Failure met lege magazijn-id gooit IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            MagazijnResult.Failure("", "Magazijn", RuntimeException(), MagazijnFault.INTERNAL_BUG)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MagazijnResult.Failure("   ", "Magazijn", RuntimeException(), MagazijnFault.INTERNAL_BUG)
        }
    }

    @Test
    fun `MagazijnBerichtenResponse met berichten`() {
        val response = MagazijnBerichtenResponse(
            listOf(
                MagazijnBericht(
                    berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    afzender = "00000001234567890000",
                    ontvanger = MagazijnBericht.Identificatienummer("BSN", "999993653"),
                    onderwerp = "test",
                    inhoud = "inhoud",
                    publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
                ),
            ),
        )

        assertEquals(1, response.berichten.size)
        assertNotNull(response.toString())
    }
}
