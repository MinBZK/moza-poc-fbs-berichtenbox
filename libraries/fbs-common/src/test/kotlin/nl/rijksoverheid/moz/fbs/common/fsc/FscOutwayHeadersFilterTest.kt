package nl.rijksoverheid.moz.fbs.common.fsc

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.UUID

class FscOutwayHeadersFilterTest {

    private fun runFilter(grantHash: String): MultivaluedHashMap<String, Any> {
        val ctx = mockk<ClientRequestContext>()
        val headers = MultivaluedHashMap<String, Any>()
        every { ctx.headers } returns headers
        every { ctx.uri } returns URI.create("https://outway.voorbeeld.test/api/v1/berichten")

        FscOutwayHeadersFilter(grantHash).filter(ctx)

        return headers
    }

    @Test
    fun `filter zet Fsc-Grant-Hash gelijk aan de meegegeven waarde`() {
        val headers = runFilter("abc123")

        assertEquals(listOf("abc123"), headers["Fsc-Grant-Hash"])
    }

    @Test
    fun `filter zet een Fsc-Transaction-Id die als UUID v7 parseert`() {
        val headers = runFilter("abc123")

        val transactionId = headers.getFirst("Fsc-Transaction-Id") as String
        val uuid = UUID.fromString(transactionId)

        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
    }

    @Test
    fun `twee invocaties leveren twee verschillende transaction-ids`() {
        val eersteHeaders = runFilter("abc123")
        val tweedeHeaders = runFilter("abc123")

        assertNotEquals(
            eersteHeaders.getFirst("Fsc-Transaction-Id"),
            tweedeHeaders.getFirst("Fsc-Transaction-Id"),
        )
    }
}
