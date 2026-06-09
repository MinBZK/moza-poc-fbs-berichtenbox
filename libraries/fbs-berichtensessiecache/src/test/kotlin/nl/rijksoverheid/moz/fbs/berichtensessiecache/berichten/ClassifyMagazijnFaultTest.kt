package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.core.JsonParseException
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.TimeoutException
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnAggregatieBulkhead
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnCircuitBreaker
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClientFactory
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnFault
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResolver
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResponseOverflow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Direct-tests op `classifyMagazijnFault` — buiten de end-to-end SSE-pipeline om.
 * Pinned alle enum-takken inclusief edge-cases (WAE met null Response, status=0,
 * status=399) en cause-walking via geneste exceptions.
 */
@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class ClassifyMagazijnFaultTest {

    private val service = BerichtensessiecacheService(
        mockk<BerichtenCache>(),
        mockk<MagazijnClientFactory>(),
        mockk<BerichtValidator>(relaxed = true),
        mockk<MagazijnResolver>(relaxed = true),
        innerTimeoutSeconds = 2L,
        outerAwaitSeconds = 3L,
        maxBerichtenPerMagazijn = 1000,
        magazijnQueryTimeoutSeconds = 10L,
        magazijnReadTimeoutMs = 12000L,
        cacheAwaitTimeoutSeconds = 5L,
        bulkhead = MagazijnAggregatieBulkhead(maxConcurrent = 20),
        circuitBreaker = MagazijnCircuitBreaker(drempel = 3, openSeconds = 30L),
    ).also { it.valideerTimeouts() }

    @Test
    fun `TimeoutException direct = TIMEOUT`() {
        assertEquals(MagazijnFault.TIMEOUT, service.classifyMagazijnFault(TimeoutException()))
    }

    @Test
    fun `MagazijnResponseOverflow direct = OVERFLOW`() {
        assertEquals(MagazijnFault.OVERFLOW, service.classifyMagazijnFault(MagazijnResponseOverflow("te veel")))
    }

    @Test
    fun `JsonProcessingException direct = MALFORMED`() {
        assertEquals(MagazijnFault.MALFORMED, service.classifyMagazijnFault(JsonParseException(null, "oeps")))
    }

    @Test
    fun `ProcessingException met JPE-cause = MALFORMED`() {
        assertEquals(
            MagazijnFault.MALFORMED,
            service.classifyMagazijnFault(ProcessingException(JsonParseException(null, "oeps"))),
        )
    }

    @Test
    fun `diep-geneste JPE (CompletionException-achtige wrap) = MALFORMED`() {
        val diep = RuntimeException("outer", RuntimeException("middle", JsonParseException(null, "diep")))
        assertEquals(MagazijnFault.MALFORMED, service.classifyMagazijnFault(diep))
    }

    @Test
    fun `ConnectException direct = NETWORK`() {
        assertEquals(MagazijnFault.NETWORK, service.classifyMagazijnFault(java.net.ConnectException("refused")))
    }

    @Test
    fun `ProcessingException zonder cause = NETWORK`() {
        assertEquals(MagazijnFault.NETWORK, service.classifyMagazijnFault(ProcessingException("net")))
    }

    @Test
    fun `CancellationException = NETWORK (annulering is geen INTERNAL_BUG)`() {
        assertEquals(
            MagazijnFault.NETWORK,
            service.classifyMagazijnFault(java.util.concurrent.CancellationException("cancelled")),
        )
    }

    @Test
    fun `WebApplicationException 500 = HTTP_5XX`() {
        assertEquals(
            MagazijnFault.HTTP_5XX,
            service.classifyMagazijnFault(WebApplicationException(Response.status(500).build())),
        )
    }

    @Test
    fun `WebApplicationException 403 = HTTP_4XX`() {
        assertEquals(
            MagazijnFault.HTTP_4XX,
            service.classifyMagazijnFault(WebApplicationException(Response.status(403).build())),
        )
    }

    @Test
    fun `WebApplicationException zonder bruikbare status = INTERNAL_BUG`() {
        // Raw WAE("oeps") zonder Response heeft status=500 (default), valt onder HTTP_5XX.
        // Maar handmatig-geconstrueerde WAE met null response geeft status=0 → INTERNAL_BUG.
        val wae = object : WebApplicationException("raw") {
            override fun getResponse(): Response? = null
        }
        assertEquals(MagazijnFault.INTERNAL_BUG, service.classifyMagazijnFault(wae))
    }

    @Test
    fun `WebApplicationException status 399 (geen 4xx, geen 5xx) = INTERNAL_BUG`() {
        // Edge: 399 valt buiten beide HTTP-bereiken → eigen-bug.
        assertEquals(
            MagazijnFault.INTERNAL_BUG,
            service.classifyMagazijnFault(WebApplicationException(Response.status(399).build())),
        )
    }

    @Test
    fun `NullPointerException = INTERNAL_BUG`() {
        assertEquals(MagazijnFault.INTERNAL_BUG, service.classifyMagazijnFault(NullPointerException("npe")))
    }

    @Test
    fun `IllegalStateException = INTERNAL_BUG`() {
        assertEquals(MagazijnFault.INTERNAL_BUG, service.classifyMagazijnFault(IllegalStateException("oeps")))
    }

    @Test
    fun `WAE met 500-status diep gewrapt = HTTP_5XX (cause-walking)`() {
        val wae = WebApplicationException(Response.status(500).build())
        val diep = RuntimeException("outer", wae)
        assertEquals(MagazijnFault.HTTP_5XX, service.classifyMagazijnFault(diep))
    }
}
