package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.common.profiel.PartijRequest
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Contracttest van [ProfielServiceClient] tegen een WireMock-stub. Een uitgaande client
 * hoort zo'n laag te hebben: andere tests vervangen de client via een `@Mock` CDI-bean en
 * valideren daarmee niet de échte JSON-serialisatie van de aanvraag, de deserialisatie van
 * het antwoord of de HTTP-foutmapping.
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
            post(urlEqualTo("/api/profielservice/v1/partij"))
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

        val partij = client.getPartij(PartijRequest("BSN", "999993653"))

        assertEquals(1, partij.voorkeuren.size)
        assertEquals("OntvangViaBerichtenbox", partij.voorkeuren[0].voorkeurType)
        assertEquals("true", partij.voorkeuren[0].waarde)
        assertEquals("OIN", partij.voorkeuren[0].scopes[0].partij?.identificatieType)
        assertEquals("00000001003214345000", partij.voorkeuren[0].scopes[0].partij?.identificatieNummer)
    }

    @Test
    fun `404 wordt vertaald naar WebApplicationException met status 404`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/profielservice/v1/partij"))
                .willReturn(aResponse().withStatus(404)),
        )

        // Quarkus REST Reactive werpt `ClientWebApplicationException` (subtype
        // van WebApplicationException) voor alle 4xx — niet `NotFoundException`.
        // BerichtValidatieService filtert daarom expliciet op status 404; deze
        // assert borgt het contract waar die filter op vertrouwt.
        val ex = assertThrows(WebApplicationException::class.java) {
            client.getPartij(PartijRequest("BSN", "999993653"))
        }
        assertEquals(404, ex.response?.status)
    }

    @Test
    fun `5xx wordt vertaald naar WebApplicationException`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/profielservice/v1/partij"))
                .willReturn(aResponse().withStatus(500).withBody("internal error")),
        )

        // `@Retry(retryOn = [ProcessingException::class])` retryt alleen op transient
        // netwerk-fouten; een expliciete 5xx-respons is een deterministisch upstream-
        // antwoord en wordt direct doorgegeven. De buitenste service vangt het via
        // skipOn (WebApplicationException) en laat het de circuit breaker NIET trippen
        // — een single-shot 5xx mag niet de hele aanlever-flow offline halen.
        assertThrows(WebApplicationException::class.java) {
            client.getPartij(PartijRequest("BSN", "999993653"))
        }
    }

    @Test
    fun `malformed JSON wordt als fout gemeld (geen stille fallback)`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/profielservice/v1/partij"))
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
            client.getPartij(PartijRequest("BSN", "999993653"))
        }
    }

    @Test
    fun `lege body op 200 levert lege PartijResponse op`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/profielservice/v1/partij"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}"),
                ),
        )

        val partij = client.getPartij(PartijRequest("BSN", "999993653"))
        assertEquals(0, partij.voorkeuren.size, "Lege body MOET default lege voorkeuren-lijst opleveren")
    }

    @Test
    fun `onbekende velden in respons worden genegeerd (forward-compat)`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/profielservice/v1/partij"))
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

        val partij = client.getPartij(PartijRequest("BSN", "999993653"))
        assertEquals(0, partij.voorkeuren.size)
    }

    @Test
    fun `KVK-identificatie gaat in dezelfde body-vorm als BSN`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/profielservice/v1/partij"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")),
        )

        client.getPartij(PartijRequest("KVK", "12345678"))

        // Twee typen in de suite, niet één: een serialisatie die het type hardcodeert of
        // laat vallen slaagt op een enkel geval en valt hier alsnog door de mand.
        wireMock.verify(
            postRequestedFor(urlEqualTo("/api/profielservice/v1/partij"))
                .withRequestBody(
                    equalToJson("""{"identificatieType":"KVK","identificatieNummer":"12345678"}"""),
                ),
        )
    }

    @Test
    fun `zonder grant-hash draagt de Profiel-call geen FSC-outway-headers`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/profielservice/v1/partij")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren":[]}""")
            )
        )

        client.getPartij(PartijRequest("BSN", "999993653"))

        wireMock.verify(
            postRequestedFor(urlEqualTo("/api/profielservice/v1/partij"))
                .withoutHeader("Fsc-Grant-Hash")
                .withoutHeader("Fsc-Transaction-Id")
        )
    }

    @Test
    fun `identificatienummer gaat in de request-body en niet in het webadres`() {
        // Catch-all-stub zodat de call slaagt wélke vorm de client ook stuurt; de verify
        // hieronder is de echte assert. Zonder de catch-all zou een afwijkende vorm al op
        // een 404 stranden en daarmee verbergen wát er verstuurd werd.
        wireMock.stubFor(
            any(anyUrl()).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren":[]}"""),
            ),
        )

        client.getPartij(PartijRequest("BSN", "999993653"))

        wireMock.verify(
            postRequestedFor(urlEqualTo("/api/profielservice/v1/partij"))
                // equalToJson matcht ongeacht de header; zonder deze assert zou het wegvallen
                // van @Consumes de suite groen laten terwijl de echte dienst met 415 antwoordt.
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(
                    equalToJson("""{"identificatieType":"BSN","identificatieNummer":"999993653"}"""),
                ),
        )

        // Het identificatienummer mag in geen enkel opgevraagd webadres voorkomen:
        // webadressen worden onderweg breder vastgelegd dan een request-body.
        wireMock.verify(0, anyRequestedFor(urlMatching(".*999993653.*")))
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
