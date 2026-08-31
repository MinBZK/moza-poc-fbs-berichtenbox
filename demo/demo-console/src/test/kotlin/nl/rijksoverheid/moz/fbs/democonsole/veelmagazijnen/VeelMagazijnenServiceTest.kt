package nl.rijksoverheid.moz.fbs.democonsole.veelmagazijnen

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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

    private fun mappingsRespons(code: Int, body: WireMockMappings) =
        mockk<Response>(relaxed = true) {
            every { status } returns code
            every { readEntity(WireMockMappings::class.java) } returns body
        }

    private fun mappingsMet(vararg ids: String) =
        mappingsRespons(200, WireMockMappings(ids.map { WireMockMapping(it) } + WireMockMapping(BASIS_MAPPING_ID)))

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
        every { wiremock.mappings() } returns mappingsRespons(
            200,
            WireMockMappings((1..5).map { WireMockMapping("00000000-0000-0000-0000-%012d".format(it)) }),
        )

        assertEquals(mapOf("actief" to 5, "totaal" to 5), service.status())
    }

    @Test
    fun `status negeert een mapping zonder id`() {
        // Het schema van de admin-API staat niet vast; een mapping in een onverwachte vorm mag de
        // uitlezing niet laten falen, en is per definitie niet een van onze overlays.
        every { wiremock.mappings() } returns mappingsRespons(
            200,
            WireMockMappings(listOf(WireMockMapping(null), WireMockMapping(VeelMagazijnenService.overlayId(5)))),
        )

        assertEquals(mapOf("actief" to 4, "totaal" to 5), service.status())
    }

    @Test
    fun `status telt dezelfde overlay-id maar een keer`() {
        // Twee mappings met dezelfde id — twee consoles die tegelijk schuiven, of een POST die
        // dubbel landde — zouden bij het tellen van voorkomens het actieve aantal onder nul duwen.
        every { wiremock.mappings() } returns mappingsMet(
            VeelMagazijnenService.overlayId(5),
            VeelMagazijnenService.overlayId(5),
        )

        assertEquals(mapOf("actief" to 4, "totaal" to 5), service.status())
    }

    @Test
    fun `status weigert een lege mappinglijst in plaats van alles actief te melden`() {
        // Stubs zonder mappings serveren niets: elk magazijnverzoek loopt op een 404. "5 van 5
        // actief" melden verbergt precies die kapotte stack.
        every { wiremock.mappings() } returns mappingsRespons(200, WireMockMappings(emptyList()))

        val fout = assertThrows(IllegalStateException::class.java) { service.status() }

        assertTrue(fout.message!!.contains("geen enkele mapping"), "melding moet de oorzaak noemen, was: ${fout.message}")
    }

    @Test
    fun `status weigert een niet-2xx antwoord van WireMock`() {
        // De default rest-client-mapper staat uit, dus zonder deze toets komt een foutbody binnen
        // als een lege mappings-lijst — en dat leest als "alles actief".
        every { wiremock.mappings() } returns mappingsRespons(503, WireMockMappings(emptyList()))

        val fout = assertThrows(IllegalStateException::class.java) { service.status() }

        assertTrue(fout.message!!.contains("503"), "melding moet de status noemen, was: ${fout.message}")
    }

    @Test
    fun `een omgeving zonder stub-magazijnen levert nul van nul`() {
        val zonder = VeelMagazijnenService(wiremock, aantal = 0)

        every { wiremock.mappings() } returns mappingsMet()

        assertEquals(mapOf("actief" to 0, "totaal" to 0), zonder.status())
    }

    @Test
    fun `mappings uit echt WireMock-JSON leveren de id op`() {
        // De veldnaam is een afspraak met een vreemde admin-API. Parseert `id` niet, dan telt de
        // service nul overlays en meldt het paneel structureel dat alles actief is.
        val json = """
            {"mappings":[
              {"id":"9f562886-1bb2-4e57-92f7-e9548482cabc","uuid":"9f562886-1bb2-4e57-92f7-e9548482cabc",
               "priority":5,"request":{"urlPath":"/m04/api/v1/berichten","method":"GET"},
               "response":{"status":200}}
            ],"meta":{"total":1}}
        """.trimIndent()

        val gelezen = jacksonObjectMapper().readValue(json, WireMockMappings::class.java)

        assertEquals(listOf("9f562886-1bb2-4e57-92f7-e9548482cabc"), gelezen.mappings.map { it.id })
    }

    @Test
    fun `een antwoord zonder mappings-veld levert een lege lijst en geen fout`() {
        val gelezen = jacksonObjectMapper().readValue("""{"errors":[{"code":10}]}""", WireMockMappings::class.java)

        assertEquals(emptyList<WireMockMapping>(), gelezen.mappings)
    }

    private companion object {

        const val BASIS_MAPPING_ID = "00000000-0000-0000-0000-000000000001"
    }
}
