package nl.rijksoverheid.moz.fbs.democonsole.veelmagazijnen

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class VeelMagazijnenServiceTest {

    private val wiremock = mockk<WireMockAdminClient>(relaxed = false)
    private val service = VeelMagazijnenService(wiremock, aantal = 5)

    private fun respons(code: Int) = mockk<Response>(relaxed = true) { every { status } returns code }

    @Test
    fun `zetActief laat 1 tot k actief en zet k+1 tot n op storing`() {
        every { wiremock.verwijderOverlay(any()) } returns respons(200)
        every { wiremock.voegOverlayToe(any()) } returns respons(201)

        service.zetActief(3)

        // Elke stub krijgt eerst een DELETE (idempotent), ongeacht actief/inactief.
        verify(exactly = 5) { wiremock.verwijderOverlay(any()) }
        // 4..5 op storing → verse 503-overlay met de Host-matcher van dat magazijn.
        verify {
            wiremock.voegOverlayToe(
                match {
                    it.id == VeelMagazijnenService.overlayId(4) &&
                        it.response.status == 503 &&
                        it.request.urlPath == VeelMagazijnenService.STUB_PAD &&
                        it.request.headers["Host"]?.matches == VeelMagazijnenService.hostPatroon(4)
                },
            )
        }
        verify { wiremock.voegOverlayToe(match { it.id == VeelMagazijnenService.overlayId(5) }) }
        verify(exactly = 2) { wiremock.voegOverlayToe(any()) }
        verify(exactly = 0) { wiremock.voegOverlayToe(match { it.id == VeelMagazijnenService.overlayId(3) }) }
    }

    @Test
    fun `zetActief 0 zet alles op storing`() {
        every { wiremock.verwijderOverlay(any()) } returns respons(200)
        every { wiremock.voegOverlayToe(any()) } returns respons(201)

        service.zetActief(0)

        verify(exactly = 5) { wiremock.voegOverlayToe(any()) }
    }

    @Test
    fun `zetActief n laat alles actief`() {
        every { wiremock.verwijderOverlay(any()) } returns respons(200)

        service.zetActief(5)

        verify(exactly = 5) { wiremock.verwijderOverlay(any()) }
        verify(exactly = 0) { wiremock.voegOverlayToe(any()) }
    }

    @Test
    fun `zetActief buiten 0 tot n faalt`() {
        assertThrows(IllegalArgumentException::class.java) { service.zetActief(6) }
        assertThrows(IllegalArgumentException::class.java) { service.zetActief(-1) }
    }

    @Test
    fun `reset herlaadt de mappings van schijf`() {
        every { wiremock.herlaad() } returns respons(200)

        service.reset()

        verify { wiremock.herlaad() }
    }
}
