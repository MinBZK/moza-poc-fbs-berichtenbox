package nl.rijksoverheid.moz.fbs.democonsole.veelmagazijnen

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
        // 4..5 op storing → verse 503-overlay op het pad-prefix van dat magazijn.
        verify {
            wiremock.voegOverlayToe(
                match {
                    it.id == VeelMagazijnenService.overlayId(4) &&
                        it.response.status == 503 &&
                        it.request.urlPath == VeelMagazijnenService.stubPad(4)
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
    fun `een 404 bij het weghalen van een overlay is geen fout`() {
        // 404 = er stond geen overlay; dat is precies de gewenste eindtoestand.
        every { wiremock.verwijderOverlay(any()) } returns respons(404)

        service.zetActief(5)

        verify(exactly = 5) { wiremock.verwijderOverlay(any()) }
    }

    @Test
    fun `een andere foutstatus bij het weghalen van een overlay faalt zichtbaar`() {
        // Zonder deze toets meldt het paneel "actief: 5 van 5" terwijl de magazijnen op 503 blijven.
        every { wiremock.verwijderOverlay(any()) } returns respons(500)

        val fout = assertThrows(IllegalStateException::class.java) { service.zetActief(5) }

        assertTrue(fout.message!!.contains("500"), "melding moet de status noemen, was: ${fout.message}")
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

    private fun mappingsMet(vararg ids: String) =
        WireMockMappings(ids.map { WireMockMapping(it) } + WireMockMapping(BASIS_MAPPING_ID))

    @Test
    fun `status telt de magazijnen zonder storing als actief`() {
        every { wiremock.mappings() } returns mappingsMet(
            VeelMagazijnenService.overlayId(4),
            VeelMagazijnenService.overlayId(5),
        )

        assertEquals(mapOf("actief" to 3, "totaal" to 5), service.status())
    }

    @Test
    fun `status zonder overlays meldt alles actief`() {
        every { wiremock.mappings() } returns mappingsMet()

        assertEquals(mapOf("actief" to 5, "totaal" to 5), service.status())
    }

    @Test
    fun `status met precies een overlay meldt er een minder`() {
        // Onderscheidt "telt of er uberhaupt een overlay is" van "telt hoeveel"; met twee
        // overlays valt dat verschil niet op.
        every { wiremock.mappings() } returns mappingsMet(VeelMagazijnenService.overlayId(5))

        assertEquals(mapOf("actief" to 4, "totaal" to 5), service.status())
    }

    @Test
    fun `status met alles op storing meldt nul actief`() {
        every { wiremock.mappings() } returns mappingsMet(*(1..5).map(VeelMagazijnenService::overlayId).toTypedArray())

        assertEquals(mapOf("actief" to 0, "totaal" to 5), service.status())
    }

    @Test
    fun `status telt alleen onze eigen overlays, niet de base-mappings`() {
        // De stub-magazijnen laden n base-mappings van schijf. Die meetellen zou elk magazijn
        // dubbel als storing zien en het paneel structureel nul actief laten melden.
        every { wiremock.mappings() } returns WireMockMappings(
            (1..5).map { WireMockMapping("00000000-0000-0000-0000-%012d".format(it)) },
        )

        assertEquals(mapOf("actief" to 5, "totaal" to 5), service.status())
    }

    @Test
    fun `status negeert een mapping zonder id`() {
        // WireMock geeft de id van een inline gedefinieerde stub niet altijd mee; zonder deze
        // afhandeling loopt de telling stuk op een null.
        every { wiremock.mappings() } returns WireMockMappings(
            listOf(WireMockMapping(null), WireMockMapping(VeelMagazijnenService.overlayId(5))),
        )

        assertEquals(mapOf("actief" to 4, "totaal" to 5), service.status())
    }

    private companion object {

        const val BASIS_MAPPING_ID = "00000000-0000-0000-0000-000000000001"
    }
}
