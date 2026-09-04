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
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnPaginaLezer
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResolver
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResponseOverflow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Direct-tests op `classifyMagazijnFault` en `isAfbreking` — buiten de end-to-end
 * SSE-pipeline om. Pinnen alle enum-takken inclusief edge-cases (WAE met null Response,
 * status onder 300, 3xx) en cause-walking via geneste exceptions.
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
        paginaLezer = MagazijnPaginaLezer(paginaGrootte = 100, maxBerichtenPerMagazijn = 1000),
        magazijnQueryTimeoutSeconds = 10L,
        magazijnReadTimeoutMs = 12000L,
        cacheAwaitTimeoutSeconds = 5L,
        bulkhead = MagazijnAggregatieBulkhead(maxConcurrent = 20, maxParallelPerRonde = 20, maxWachttijdMs = 15000L),
        circuitBreaker = MagazijnCircuitBreaker(drempel = 3, openSeconds = 30L),
    ).also { it.valideerTimeouts() }

    @Test
    fun `TimeoutException direct = TIMEOUT`() {
        assertEquals(MagazijnFault.TIMEOUT, service.classifyMagazijnFault(TimeoutException()))
    }

    @Test
    fun `MagazijnResponseOverflow direct = OVERFLOW`() {
        assertEquals(MagazijnFault.OVERFLOW, service.classifyMagazijnFault(MagazijnResponseOverflow()))
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

    /**
     * Een 3xx die als fout terugkomt betekent dat de client de doorverwijzing niet volgde: het
     * magazijn staat op een ander adres dan geconfigureerd. Een eigen fault, zodat het log de
     * werkelijke oorzaak noemt in plaats van een 4xx die er niet is.
     */
    @ParameterizedTest
    @ValueSource(ints = [300, 301, 302, 307, 308, 399])
    fun `WebApplicationException met 3xx = HTTP_3XX (magazijn staat elders, geen eigen bug)`(status: Int) {
        assertEquals(
            MagazijnFault.HTTP_3XX,
            service.classifyMagazijnFault(WebApplicationException(Response.status(status).build())),
        )
    }

    @Test
    fun `WebApplicationException met status onder 300 = INTERNAL_BUG`() {
        // Een 2xx die als fout terugkomt kan geen upstream-signaal zijn; dan zit de fout bij ons.
        assertEquals(
            MagazijnFault.INTERNAL_BUG,
            service.classifyMagazijnFault(WebApplicationException(Response.status(299).build())),
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

    // --- isAfbreking: normaal gedrag mag geen storingsmelding opleveren ---

    @Test
    fun `CancellationException is een afbreking`() {
        assertTrue(service.isAfbreking(java.util.concurrent.CancellationException("client weg")))
    }

    @Test
    fun `InterruptedException is een afbreking`() {
        assertTrue(service.isAfbreking(InterruptedException("pod gaat uit")))
    }

    @Test
    fun `een diep gewrapte annulering is een afbreking`() {
        val diep = RuntimeException("outer", RuntimeException("middle", java.util.concurrent.CancellationException("weg")))

        assertTrue(service.isAfbreking(diep))
    }

    @Test
    fun `een gewone fout is geen afbreking`() {
        assertFalse(service.isAfbreking(IllegalStateException("redis stuk")))
        assertFalse(service.isAfbreking(java.net.ConnectException("refused")))
    }

    // --- de bedrading van de opstartcontrole ---

    /**
     * Zonder observer maakt ArC deze bean pas bij het eerste request aan, en dan komt een
     * ongeldige timeout-combinatie pas aan het licht als een gebruiker een ophaalronde start.
     * De validatie zelf is elders gedekt; deze test bewaakt dat hij ook echt aan het opstarten
     * hangt en niet pas bij het eerste request draait.
     */
    @Test
    fun `de timeout-controle hangt aan het opstarten`() {
        val observer = BerichtensessiecacheService::class.java.methods.singleOrNull { methode ->
            methode.parameterTypes.contentEquals(arrayOf(io.quarkus.runtime.StartupEvent::class.java)) &&
                methode.parameterAnnotations.any { annotaties ->
                    annotaties.any { it.annotationClass == jakarta.enterprise.event.Observes::class }
                }
        }

        assertNotNull(observer, "geen @Observes StartupEvent-methode: de validatie draait dan pas bij het eerste request")
    }
}
