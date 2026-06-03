package nl.rijksoverheid.moz.fbs.berichtenmagazijn.beheer

import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtMetVerwijderdOp
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Dekt de race-tak van [BerichtBeheerService.verwijder] via een ingestoken
 * BerichtRepository-mock. Een echte concurrente DELETE-race is niet
 * reproduceerbaar binnen één @QuarkusTest; deze test forceert specifiek de
 * combinaties die de re-check-paden afdwingen.
 */
@QuarkusTest
class BerichtBeheerServiceRaceQuarkusTest {

    @Inject
    lateinit var service: BerichtBeheerService

    private val ontvanger: Identificatienummer = Bsn("999993653")

    private fun bericht(): Bericht = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = Oin("00000001003214345000"),
        ontvanger = ontvanger,
        onderwerp = "Race-test",
        inhoud = "Inhoud",
        tijdstipOntvangst = Instant.parse("2026-05-13T10:00:00Z"),
        publicatiedatum = Instant.parse("2026-05-13T10:00:00Z"),
    )

    private fun installRepo(mock: BerichtRepository) {
        QuarkusMock.installMockForType(mock, BerichtRepository::class.java)
    }

    @Test
    fun `softDelete=false met re-check-verified verwijdering eindigt zonder fout`() {
        val b = bericht()
        val mock = mockk<BerichtRepository>(relaxed = false)
        every { mock.findIncludingDeleted(b.berichtId) } returnsMany listOf(
            BerichtMetVerwijderdOp(b, null),
            BerichtMetVerwijderdOp(b, Instant.parse("2026-05-13T11:00:00Z")),
        )
        every { mock.softDelete(b.berichtId, ontvanger, any()) } returns false
        installRepo(mock)

        service.verwijder(b.berichtId, ontvanger)
    }

    @Test
    fun `softDelete=false met bericht hard-verdwenen gooit IllegalStateException`() {
        val b = bericht()
        val mock = mockk<BerichtRepository>(relaxed = false)
        every { mock.findIncludingDeleted(b.berichtId) } returnsMany listOf(
            BerichtMetVerwijderdOp(b, null),
            null,
        )
        every { mock.softDelete(b.berichtId, ontvanger, any()) } returns false
        installRepo(mock)

        assertThrows(IllegalStateException::class.java) { service.verwijder(b.berichtId, ontvanger) }
    }

    @Test
    fun `softDelete=false maar bericht niet verwijderd gooit IllegalStateException`() {
        val b = bericht()
        val mock = mockk<BerichtRepository>(relaxed = false)
        every { mock.findIncludingDeleted(b.berichtId) } returns BerichtMetVerwijderdOp(b, null)
        every { mock.softDelete(b.berichtId, ontvanger, any()) } returns false
        installRepo(mock)

        assertThrows(IllegalStateException::class.java) { service.verwijder(b.berichtId, ontvanger) }
    }
}
