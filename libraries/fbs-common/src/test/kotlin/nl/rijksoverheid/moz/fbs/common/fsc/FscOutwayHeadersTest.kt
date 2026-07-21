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

class FscOutwayHeadersTest {

    private fun zetHeaders(grantHash: String): MultivaluedHashMap<String, Any> {
        val ctx = mockk<ClientRequestContext>()
        val headers = MultivaluedHashMap<String, Any>()
        every { ctx.headers } returns headers
        every { ctx.uri } returns URI.create("https://outway.voorbeeld.test/api/profielservice/v1/BSN/999993653")

        FscOutwayHeaders.zet(ctx, grantHash)

        return headers
    }

    @Test
    fun `zet de grant-hash ongewijzigd op de Fsc-Grant-Hash-header`() {
        val headers = zetHeaders("abc123")

        assertEquals(listOf("abc123"), headers[FscOutwayHeaders.GRANT_HASH_HEADER])
    }

    @Test
    fun `zet een Fsc-Transaction-Id die als UUID v7 parseert`() {
        val headers = zetHeaders("abc123")

        val uuid = UUID.fromString(headers.getFirst(FscOutwayHeaders.TRANSACTION_ID_HEADER) as String)

        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
    }

    @Test
    fun `twee invocaties leveren twee verschillende transaction-ids`() {
        val eerste = zetHeaders("abc123")
        val tweede = zetHeaders("abc123")

        assertNotEquals(
            eerste.getFirst(FscOutwayHeaders.TRANSACTION_ID_HEADER),
            tweede.getFirst(FscOutwayHeaders.TRANSACTION_ID_HEADER),
        )
    }
}
