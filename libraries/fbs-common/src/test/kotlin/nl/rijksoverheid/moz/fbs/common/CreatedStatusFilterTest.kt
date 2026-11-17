package nl.rijksoverheid.moz.fbs.common

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.UriInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CreatedStatusFilterTest {

    private val filter = CreatedStatusFilter()

    private fun run(method: String, status: Int, pad: String = "aanleveringen"): ContainerResponseContext {
        val req = mockk<ContainerRequestContext>()
        val res = mockk<ContainerResponseContext>(relaxed = true)
        val uriInfo = mockk<UriInfo>()
        every { req.method } returns method
        every { req.uriInfo } returns uriInfo
        every { uriInfo.path } returns pad
        every { res.status } returns status
        filter.filter(req, res)
        return res
    }

    // Een actiepad maakt niets aan: `POST /sessies/huidige/_verlengen` antwoordt 200, geen 201.
    @ParameterizedTest(name = "POST {0}")
    @ValueSource(strings = ["sessies/huidige/_verlengen", "/sessies/huidige/_verlengen", "berichten/_zoeken", "_actie"])
    fun `POST op een actiepad met 200 blijft 200`(pad: String) {
        val res = run("POST", 200, pad)
        verify(exactly = 0) { res.status = any() }
    }

    // Alleen het laatste segment telt: een `_` eerder in het pad maakt van een aanmaak geen actie.
    @ParameterizedTest(name = "POST {0}")
    @ValueSource(strings = ["sessies", "/sessies", "_beheer/sessies", "sessies/huidige_verlengen"])
    fun `POST op een gewoon pad met 200 wordt 201`(pad: String) {
        val res = run("POST", 200, pad)
        verify { res.status = 201 }
    }

    @Test
    fun `POST met 200 wordt geflipt naar 201`() {
        val res = run("POST", 200)
        verify { res.status = 201 }
    }

    @Test
    fun `POST met 204 blijft 204`() {
        val res = run("POST", 204)
        verify(exactly = 0) { res.status = any() }
    }

    @Test
    fun `POST met 500 blijft 500`() {
        val res = run("POST", 500)
        verify(exactly = 0) { res.status = any() }
    }

    @Test
    fun `GET met 200 blijft 200`() {
        val res = run("GET", 200)
        verify(exactly = 0) { res.status = any() }
    }

    @Test
    fun `PUT met 200 blijft 200`() {
        val res = run("PUT", 200)
        verify(exactly = 0) { res.status = any() }
    }

    @Test
    fun `DELETE met 200 blijft 200`() {
        val res = run("DELETE", 200)
        verify(exactly = 0) { res.status = any() }
    }
}
