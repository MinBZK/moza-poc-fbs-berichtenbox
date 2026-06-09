package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.EventType
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockBerichtenCache
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * End-to-end gedrag van de per-magazijn circuit breaker via de aggregatie-pipeline.
 *
 * Pint de #557-kern: na herhaalde storingen bij één magazijn wordt dat magazijn tijdelijk
 * overgeslagen (snelle CIRCUIT_OPEN-fail i.p.v. opnieuw op de timeout wachten), terwijl een
 * gezond magazijn ongemoeid blijft — een trage/dode leverancier raakt dus alléén zijn eigen
 * gebruikers. Default circuit-drempel (3) en open-venster (30s) gelden.
 */
@QuarkusTest
@TestProfile(WireMockTestProfile::class)
@QuarkusTestResource(WireMockMagazijnResource::class)
class MagazijnCircuitBreakerIntegrationTest {

    @Inject
    lateinit var sessiecache: Sessiecache

    @Inject
    internal lateinit var cache: MockBerichtenCache

    @Inject
    internal lateinit var circuitBreaker: MagazijnCircuitBreaker

    private val serverA get() = WireMockMagazijnResource.serverA!!
    private val serverB get() = WireMockMagazijnResource.serverB!!

    @BeforeEach
    fun setUp() {
        serverA.resetAll()
        serverB.resetAll()
        cache.clear()
        circuitBreaker.herstelAlles()
    }

    @Test
    fun `magazijn met herhaalde storingen wordt overgeslagen terwijl gezond magazijn beschikbaar blijft`() {
        // Magazijn-A faalt structureel (5xx), magazijn-B is gezond.
        serverA.stubFor(get(urlPathEqualTo("/api/v1/berichten")).willReturn(aResponse().withStatus(500)))
        stubSucces()

        // Drie verschillende ontvangers (elk een eigen ophaal-lock) → drie echte A-calls die
        // falen → A's circuit opent na de derde (drempel 3). B slaagt elke keer.
        verwerkOphalen(Bsn("999993653"))
        verwerkOphalen(Bsn("999990019"))
        verwerkOphalen(Bsn("999991772"))

        // Vierde ontvanger: A's circuit is nu open → A wordt overgeslagen (geen vierde A-call),
        // B blijft beschikbaar.
        val events = verwerkOphalen(Bsn("999996915"))

        val aVoltooid = voltooidVoor(events, WireMockMagazijnResource.OIN_A)
        val bVoltooid = voltooidVoor(events, WireMockMagazijnResource.OIN_B)

        assertEquals(MagazijnStatus.FOUT, aVoltooid.status, "A is overgeslagen → FOUT-status")
        assertTrue(
            aVoltooid.foutmelding?.contains("tijdelijk niet beschikbaar") == true,
            "A moet de circuit-open-melding dragen, was: ${aVoltooid.foutmelding}",
        )
        assertEquals(MagazijnStatus.OK, bVoltooid.status, "gezond magazijn B blijft beschikbaar")

        // Bewijs van de snelle skip: A is precies 3× echt bevraagd (de eerste drie), de vierde
        // is overgeslagen zonder upstream-call. B is 4× bevraagd.
        serverA.verify(3, getRequestedFor(urlPathEqualTo("/api/v1/berichten")))
        serverB.verify(4, getRequestedFor(urlPathEqualTo("/api/v1/berichten")))
    }

    private fun verwerkOphalen(ontvanger: Bsn): List<MagazijnEvent> =
        sessiecache.ophalen(ontvanger).collect().asList().await().atMost(Duration.ofSeconds(15))

    private fun voltooidVoor(events: List<MagazijnEvent>, magazijnId: String): MagazijnEvent =
        requireNotNull(
            events.firstOrNull { it.event == EventType.MAGAZIJN_BEVRAGING_VOLTOOID && it.magazijnId == magazijnId },
        ) { "Verwacht VOLTOOID-event voor magazijn $magazijnId in: $events" }

    private fun stubSucces() {
        serverB.stubFor(
            get(urlPathEqualTo("/api/v1/berichten")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"berichten":[]}"""),
            ),
        )
    }
}
