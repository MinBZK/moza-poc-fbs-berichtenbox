package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StoringServiceTest {

    private val instantie = mockk<ToxiproxyClient>(relaxed = false)
    private val tweede = mockk<ToxiproxyClient>(relaxed = false)

    private fun registerMet(vararg clients: Pair<String, ToxiproxyClient>) =
        mockk<ToxiproxyRegister> {
            every { client(any()) } answers { clients.toMap()[firstArg()] ?: error("niet geconfigureerd") }
            every { instanties() } returns clients.map { it.second }.distinct()
        }

    private val service = StoringService(registerMet("magazijn-a" to instantie, "magazijn-b" to instantie))

    // Response via MockK (relaxed sluit .use()/close() af) i.p.v. Response.ok().build(),
    // dat een JAX-RS RuntimeDelegate vereist die in een pure unittest kan ontbreken.
    private fun respons(code: Int) = mockk<Response>(relaxed = true) { every { status } returns code }

    private fun ok() = respons(200)

    private fun noContent() = respons(204)

    @Test
    fun `traag voegt een latency-toxic van 6000ms toe`() {
        every { instantie.voegToxicToe(any(), any()) } returns ok()

        service.traag("magazijn-a", 6000)

        verify { instantie.voegToxicToe("magazijn-a", ToxicVerzoek("latency", mapOf("latency" to 6000))) }
    }

    @Test
    fun `uit schakelt de proxy uit`() {
        every { instantie.zetProxy(any(), any()) } returns ok()

        service.uit("magazijn-b")

        verify { instantie.zetProxy("magazijn-b", ProxyPatch(enabled = false)) }
    }

    @Test
    fun `reset schakelt uitgeschakelde proxies weer in en wist toxics`() {
        every { instantie.proxies() } returns mapOf(
            "magazijn-a" to ProxyStatus(enabled = false, toxics = listOf(ToxicStatus("latency_downstream"))),
            "magazijn-b" to ProxyStatus(enabled = true, toxics = emptyList()),
        )
        every { instantie.zetProxy(any(), any()) } returns ok()
        every { instantie.verwijderToxic(any(), any()) } returns noContent()

        service.reset()

        verify { instantie.zetProxy("magazijn-a", ProxyPatch(enabled = true)) }
        verify { instantie.verwijderToxic("magazijn-a", "latency_downstream") }
        verify(exactly = 0) { instantie.zetProxy("magazijn-b", any()) }
    }

    @Test
    fun `reset faalt als Toxiproxy geen enkele proxy kent`() {
        // Ontbrekende of misvormde proxies.json: Toxiproxy start gezond op met nul proxies,
        // terwijl al het uitvraag- en magazijnverkeer erdoorheen loopt. De lus over een lege map
        // zou stil slagen en "alles normaal" bevestigen terwijl de keten dood is.
        every { instantie.proxies() } returns emptyMap()

        val fout = assertThrows(IllegalStateException::class.java) { service.reset() }

        assertTrue(fout.message!!.contains("proxies.json"), "melding moet naar de oorzaak wijzen, was: ${fout.message}")
    }

    @Test
    fun `een niet-2xx-respons van Toxiproxy faalt met een duidelijke melding`() {
        every { instantie.zetProxy(any(), any()) } returns respons(404)

        try {
            service.uit("magazijn-a")
            throw AssertionError("verwacht een IllegalStateException")
        } catch (fout: IllegalStateException) {
            check(fout.message!!.contains("404"))
        }
    }

    @Test
    fun `reset gaat elke instantie langs`() {
        // Op ZAD staat elke stroom op zijn eigen Toxiproxy; een reset die er maar één langsgaat
        // laat de rest van de storingen aan staan.
        every { instantie.proxies() } returns mapOf("profiel" to ProxyStatus(enabled = false))
        every { tweede.proxies() } returns mapOf("redis" to ProxyStatus(enabled = false))
        every { instantie.zetProxy(any(), any()) } returns ok()
        every { tweede.zetProxy(any(), any()) } returns ok()

        StoringService(registerMet("profiel" to instantie, "redis" to tweede)).reset()

        verify { instantie.zetProxy("profiel", ProxyPatch(enabled = true)) }
        verify { tweede.zetProxy("redis", ProxyPatch(enabled = true)) }
    }

    @Test
    fun `reset faalt zodra een van de instanties geen enkele proxy kent`() {
        every { instantie.proxies() } returns mapOf("profiel" to ProxyStatus(enabled = true))
        every { tweede.proxies() } returns emptyMap()

        val fout = assertThrows(IllegalStateException::class.java) {
            StoringService(registerMet("profiel" to instantie, "redis" to tweede)).reset()
        }

        assertTrue(fout.message!!.contains("proxies.json"), "melding moet naar de oorzaak wijzen, was: ${fout.message}")
    }
}
