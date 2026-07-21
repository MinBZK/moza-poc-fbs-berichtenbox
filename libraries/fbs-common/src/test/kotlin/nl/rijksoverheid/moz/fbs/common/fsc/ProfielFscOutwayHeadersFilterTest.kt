package nl.rijksoverheid.moz.fbs.common.fsc

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.UUID

class ProfielFscOutwayHeadersFilterTest {

    private fun runFilter(grantHash: String?): MultivaluedHashMap<String, Any> {
        val ctx = mockk<ClientRequestContext>()
        val headers = MultivaluedHashMap<String, Any>()
        every { ctx.headers } returns headers
        every { ctx.uri } returns URI.create("https://outway.voorbeeld.test/api/profielservice/v1/BSN/999993653")

        ProfielFscOutwayHeadersFilter { grantHash }.filter(ctx)

        return headers
    }

    /**
     * De drie vormen waarin "niet geconfigureerd" binnenkomt: de key ontbreekt (null), of
     * `${PROFIEL_SERVICE_GRANT_HASH:}` expandeert naar leeg/whitespace. Alle drie moeten de
     * call byte-identiek laten aan de situatie vóór deze feature — een half gezette header
     * zou de outway een onbruikbare route geven.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "   ", "\t"])
    fun `zonder bruikbare grant-hash worden geen FSC-headers gezet`(grantHash: String?) {
        val headers = runFilter(grantHash)

        assertFalse(headers.containsKey(FscOutwayHeaders.GRANT_HASH_HEADER))
        assertFalse(headers.containsKey(FscOutwayHeaders.TRANSACTION_ID_HEADER))
    }

    @Test
    fun `met grant-hash worden beide FSC-headers gezet`() {
        val headers = runFilter("profiel-hash-1")

        assertEquals(listOf("profiel-hash-1"), headers[FscOutwayHeaders.GRANT_HASH_HEADER])

        val uuid = UUID.fromString(headers.getFirst(FscOutwayHeaders.TRANSACTION_ID_HEADER) as String)

        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
    }

    @Test
    fun `omringende spaties in de config-waarde worden getrimd`() {
        val headers = runFilter("  profiel-hash-1  ")

        assertEquals(listOf("profiel-hash-1"), headers[FscOutwayHeaders.GRANT_HASH_HEADER])
    }

    @Test
    fun `twee calls op dezelfde filter leveren verschillende transaction-ids`() {
        val filter = ProfielFscOutwayHeadersFilter { "profiel-hash-1" }

        val eerste = MultivaluedHashMap<String, Any>()
        val tweede = MultivaluedHashMap<String, Any>()

        listOf(eerste, tweede).forEach { headers ->
            val ctx = mockk<ClientRequestContext>()
            every { ctx.headers } returns headers
            every { ctx.uri } returns URI.create("https://outway.voorbeeld.test/api/profielservice/v1/BSN/999993653")
            filter.filter(ctx)
        }

        assertNotEquals(
            eerste.getFirst(FscOutwayHeaders.TRANSACTION_ID_HEADER),
            tweede.getFirst(FscOutwayHeaders.TRANSACTION_ID_HEADER),
        )
    }

    @Test
    fun `de config-key blijft de gepubliceerde sleutel`() {
        // Pin de sleutel: application.properties van beide services hangt eraan, en een
        // hernoeming zou de filter stil uitschakelen in plaats van te falen.
        assertEquals("profiel-service.grant-hash", ProfielFscOutwayHeadersFilter.CONFIG_KEY)
    }
}
