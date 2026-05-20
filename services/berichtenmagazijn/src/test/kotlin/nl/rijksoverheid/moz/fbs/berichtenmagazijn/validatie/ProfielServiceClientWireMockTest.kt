package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Contracttest van [ProfielServiceClient] tegen een WireMock-stub. CLAUDE.md
 * "Testlagen → spec-driven aan de randen" eist deze laag voor uitgaande clients:
 * andere tests vervangen de client via een `@Mock` CDI-bean en valideren daarmee
 * niet de échte JSON-deserialisatie, path-encoding of HTTP-foutmapping.
 *
 * `WireMockProfielServiceTestProfile` sluit `MockProfielServiceClient` uit met
 * `quarkus.arc.exclude-types`, zodat de échte REST-client wordt geïnjecteerd.
 * `WireMockProfielServiceResource` start een WireMock-server op een dynamische
 * poort en zet `quarkus.rest-client.profiel-service.url` daarop voor de hele
 * testklasse.
 */
@QuarkusTest
@TestProfile(WireMockProfielServiceTestProfile::class)
@QuarkusTestResource(WireMockProfielServiceResource::class)
class ProfielServiceClientWireMockTest {

    @Inject
    @RestClient
    lateinit var client: ProfielServiceClient

    @BeforeEach
    fun resetStubs() {
        WireMockProfielServiceResource.server!!.resetAll()
    }

    private val wireMock get() = WireMockProfielServiceResource.server!!

    @Test
    fun `200-respons wordt correct gedeserialiseerd`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                              "partijId": 42,
                              "voorkeuren": [
                                {
                                  "voorkeurType": "OntvangViaBerichtenbox",
                                  "waarde": "true",
                                  "scopes": [
                                    { "partij": { "identificatieType": "OIN", "identificatieNummer": "00000001003214345000" } }
                                  ]
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                ),
        )

        val partij = client.getPartij("BSN", "999993653")

        assertEquals(42L, partij.partijId)
        assertEquals(1, partij.voorkeuren.size)
        assertEquals("OntvangViaBerichtenbox", partij.voorkeuren[0].voorkeurType)
        assertEquals("true", partij.voorkeuren[0].waarde)
        assertEquals("OIN", partij.voorkeuren[0].scopes[0].partij?.identificatieType)
        assertEquals("00000001003214345000", partij.voorkeuren[0].scopes[0].partij?.identificatieNummer)
    }

    @Test
    fun `404 wordt vertaald naar WebApplicationException met status 404`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(aResponse().withStatus(404)),
        )

        // Quarkus REST Reactive werpt `ClientWebApplicationException` (subtype
        // van WebApplicationException) voor alle 4xx — niet `NotFoundException`.
        // BerichtValidatieService filtert daarom expliciet op status 404; deze
        // assert borgt het contract waar die filter op vertrouwt.
        val ex = assertThrows(WebApplicationException::class.java) {
            client.getPartij("BSN", "999993653")
        }
        assertEquals(404, ex.response?.status)
    }

    @Test
    fun `5xx wordt vertaald naar WebApplicationException (telt mee voor circuit breaker)`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(aResponse().withStatus(500).withBody("internal error")),
        )

        // @Retry(maxRetries=2) op de interface betekent: 3 pogingen, dan
        // WebApplicationException(500). De buitenste circuit breaker in
        // BerichtOpslagService telt deze fout mee (niet in skipOn).
        assertThrows(WebApplicationException::class.java) {
            client.getPartij("BSN", "999993653")
        }
    }

    @Test
    fun `malformed JSON wordt als fout gemeld (geen stille fallback)`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{this is not json"),
                ),
        )

        // ProcessingException of een Jackson-afgeleide RuntimeException — het
        // exacte type hangt af van de REST-client-implementatie, maar het mag
        // GEEN happy-path-respons opleveren die stilletjes als "geen
        // voorkeuren" wordt geïnterpreteerd.
        assertThrows(RuntimeException::class.java) {
            client.getPartij("BSN", "999993653")
        }
    }

    @Test
    fun `lege body op 200 levert lege PartijResponse op`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}"),
                ),
        )

        val partij = client.getPartij("BSN", "999993653")
        assertEquals(0, partij.voorkeuren.size, "Lege body MOET default lege voorkeuren-lijst opleveren")
    }

    @Test
    fun `onbekende velden in respons worden genegeerd (forward-compat)`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                              "partijId": 1,
                              "voorkeuren": [],
                              "nieuwVeldDatNogNietBestaat": "irrelevant",
                              "createdAt": "2026-01-01T00:00:00Z"
                            }
                            """.trimIndent(),
                        ),
                ),
        )

        val partij = client.getPartij("BSN", "999993653")
        assertEquals(1L, partij.partijId)
    }

    @Test
    fun `path-encoding zet type en nummer correct in de URL`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/KVK/12345678"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")),
        )

        client.getPartij("KVK", "12345678")

        // Geen exception ⇒ WireMock matchte het verwachte path. De `urlEqualTo`-stub
        // hierboven is de echte assert: als de client-implementatie spaties of
        // path-segmenten zou veranderen, kwam er hier een 404 terug en zou de call
        // met NotFoundException falen.
    }
}

class WireMockProfielServiceTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // Sluit de @Mock-bean uit zodat de échte REST-client wordt geïnjecteerd
        // en het HTTP-pad onder test komt.
        "quarkus.arc.exclude-types" to MockProfielServiceClient::class.java.name,
    )
}

class WireMockProfielServiceResource : QuarkusTestResourceLifecycleManager {

    companion object {
        var server: WireMockServer? = null
    }

    override fun start(): Map<String, String> {
        val s = WireMockServer(wireMockConfig().dynamicPort())
        s.start()
        server = s
        return mapOf("quarkus.rest-client.profiel-service.url" to s.baseUrl())
    }

    override fun stop() {
        server?.stop()
        server = null
    }
}
