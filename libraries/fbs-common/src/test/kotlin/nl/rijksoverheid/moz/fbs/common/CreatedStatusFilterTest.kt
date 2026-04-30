package nl.rijksoverheid.moz.fbs.common

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import org.junit.jupiter.api.Test

class CreatedStatusFilterTest {

    private val filter = CreatedStatusFilter()

    private fun run(method: String, status: Int): ContainerResponseContext {
        val req = mockk<ContainerRequestContext>()
        val res = mockk<ContainerResponseContext>(relaxed = true)
        every { req.method } returns method
        every { res.status } returns status
        filter.filter(req, res)
        return res
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
