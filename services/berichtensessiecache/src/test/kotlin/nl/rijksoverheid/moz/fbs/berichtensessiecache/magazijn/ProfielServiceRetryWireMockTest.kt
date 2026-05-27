package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * WireMock-scenario-tests voor @Retry-gedrag op [ProfielServiceClient].
 *
 * Verifiëert:
 * - C: CONNECTION_RESET_BY_PEER (ProcessingException) wordt geretryed en bij succes op de
 *   tweede poging levert de resolver een resultaat op.
 * - D: 5xx (WebApplicationException) wordt NIET geretryed; de upstream ontvangt exact
 *   1 verzoek, conform de KDoc-belofte ("geen retry-storm op deterministisch antwoord").
 */
@QuarkusTest
@TestProfile(WireMockProfielServiceTestProfile::class)
@QuarkusTestResource(WireMockProfielServiceResource::class)
@QuarkusTestResource(WireMockMagazijnResource::class)
class ProfielServiceRetryWireMockTest {

    @Inject
    lateinit var resolver: MagazijnResolver

    private val wireMock get() = WireMockProfielServiceResource.server!!

    @BeforeEach
    fun resetStubs() {
        wireMock.resetAll()
    }

    // ── C: @Retry op ProcessingException werkt ───────────────────────────

    @Test
    fun `ProcessingException wordt geretryd en levert eventueel succes op`() {
        // Eerste aanroep: verbinding verbroken → ProcessingException → @Retry
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .inScenario("retry-na-tcp-reset")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("recovered"),
        )

        // Tweede aanroep (retry): succesvolle respons met lege voorkeuren.
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .inScenario("retry-na-tcp-reset")
                .whenScenarioStateIs("recovered")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"voorkeuren": []}"""),
                ),
        )

        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(20))

        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `aanhoudende ProcessingException doet maximaal 3 upstream-calls (1 + 2 retries)`() {
        // Regressie-vangnet voor @Retry(maxRetries = 2): zonder count-assert kan een
        // herconfiguratie naar maxRetries=5 of een retry-storm ongezien doorgaan.
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(20))
        }

        // 1 originele call + maxRetries=2 retries = 3 upstream-calls totaal.
        wireMock.verify(3, getRequestedFor(urlEqualTo("/api/profielservice/v1/BSN/999993653")))
    }

    // ── E: abortOn JsonProcessingException — malformed JSON 1 upstream call ──

    @Test
    fun `malformed JSON wordt niet geretryd (abortOn JsonProcessingException)`() {
        // Parse-fout is deterministisch; retry = upstream verspillen + retry-storm-risico.
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{niet-valide-json"""),
                ),
        )

        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(20))
        }

        wireMock.verify(1, getRequestedFor(urlEqualTo("/api/profielservice/v1/BSN/999993653")))
    }

    // ── D: 5xx wordt niet geretryd — exactly 1 upstream call ─────────────

    @Test
    fun `5xx wordt niet geretryd (geen retry-storm)`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(aResponse().withStatus(500)),
        )

        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(20))
        }

        // Precies 1 upstream-aanroep; geen retry bij deterministisch 5xx-antwoord.
        wireMock.verify(1, getRequestedFor(urlEqualTo("/api/profielservice/v1/BSN/999993653")))
    }
}
