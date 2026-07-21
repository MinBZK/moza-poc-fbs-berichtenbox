package nl.rijksoverheid.moz.fbs.common.fsc

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class FscOutwayHeadersTest {

    private val julLogger: Logger = Logger.getLogger(FscOutwayHeaders::class.java.name)
    private val records = mutableListOf<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records.add(record)
        }
        override fun flush() {}
        override fun close() {}
    }

    @BeforeEach
    fun installHandler() {
        julLogger.addHandler(handler)
        julLogger.level = Level.ALL
        records.clear()
    }

    @AfterEach
    fun removeHandler() {
        julLogger.removeHandler(handler)
    }

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

    @Test
    fun `debuglog bevat de host maar geen pad of query van de call`() {
        // De Profiel-call draagt de BSN in het pad (zie zetHeaders); deze log-regel
        // draait op DEBUG, dat in %dev/%test aanstaat. Een refactor die weer het
        // volledige requestContext.uri logt, zou BSN's naar de applicatielog schrijven.
        zetHeaders("abc123")

        val debugRecords = records.filter { it.level == Level.FINE }
        assertEquals(1, debugRecords.size, "verwacht 1 debug-regel — gevonden: $debugRecords")
        val rendered = debugRecords.first().let { rec ->
            rec.parameters?.let { runCatching { String.format(rec.message, *it) }.getOrDefault(rec.message) }
                ?: rec.message
        }
        assertTrue(rendered.contains("outway.voorbeeld.test"), "host moet aanwezig zijn — gevonden: $rendered")
        assertFalse(rendered.contains("999993653"), "BSN uit het pad mag NIET gelogd worden — gevonden: $rendered")
        assertFalse(rendered.contains("/api/profielservice"), "pad mag NIET gelogd worden — gevonden: $rendered")
    }
}
