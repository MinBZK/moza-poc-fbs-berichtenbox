package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Test

class StoringServiceTest {

    private val toxiproxy = mockk<ToxiproxyClient>(relaxed = false)
    private val service = StoringService(toxiproxy)

    // Response via MockK (relaxed sluit .use()/close() af) i.p.v. Response.ok().build(),
    // dat een JAX-RS RuntimeDelegate vereist die in een pure unittest kan ontbreken.
    private fun respons(code: Int) = mockk<Response>(relaxed = true) { every { status } returns code }

    private fun ok() = respons(200)

    private fun noContent() = respons(204)

    @Test
    fun `traag voegt een latency-toxic van 6000ms toe`() {
        every { toxiproxy.voegToxicToe(any(), any()) } returns ok()

        service.traag("magazijn-a", 6000)

        verify { toxiproxy.voegToxicToe("magazijn-a", ToxicVerzoek("latency", mapOf("latency" to 6000))) }
    }

    @Test
    fun `uit schakelt de proxy uit`() {
        every { toxiproxy.zetProxy(any(), any()) } returns ok()

        service.uit("magazijn-b")

        verify { toxiproxy.zetProxy("magazijn-b", ProxyPatch(enabled = false)) }
    }

    @Test
    fun `reset schakelt uitgeschakelde proxies weer in en wist toxics`() {
        every { toxiproxy.proxies() } returns mapOf(
            "magazijn-a" to ProxyStatus(enabled = false, toxics = listOf(ToxicStatus("latency_downstream"))),
            "magazijn-b" to ProxyStatus(enabled = true, toxics = emptyList()),
        )
        every { toxiproxy.zetProxy(any(), any()) } returns ok()
        every { toxiproxy.verwijderToxic(any(), any()) } returns noContent()

        service.reset()

        verify { toxiproxy.zetProxy("magazijn-a", ProxyPatch(enabled = true)) }
        verify { toxiproxy.verwijderToxic("magazijn-a", "latency_downstream") }
        verify(exactly = 0) { toxiproxy.zetProxy("magazijn-b", any()) }
    }

    @Test
    fun `een niet-2xx-respons van Toxiproxy faalt met een duidelijke melding`() {
        every { toxiproxy.zetProxy(any(), any()) } returns respons(404)

        try {
            service.uit("onbekend")
            throw AssertionError("verwacht een IllegalStateException")
        } catch (fout: IllegalStateException) {
            check(fout.message!!.contains("404"))
        }
    }
}
