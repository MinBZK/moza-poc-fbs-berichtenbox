package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockBerichtenCache
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

@QuarkusTest
@TestProfile(WireMockTestProfile::class)
@QuarkusTestResource(WireMockMagazijnResource::class)
class MagazijnClientWireMockTest {

    // Header-waarde (TYPE:WAARDE-formaat)
    private val ontvanger = "BSN:999993653"
    // Raw identificatiewaarde voor gebruik in bericht-body's (Bericht.ontvanger slaat geen type-prefix op)
    private val ontvangerWaarde = "999993653"

    // Gedeelde in-memory cache (zelfde bean over alle tests in deze klasse); per test legen
    // zodat GET /berichten-asserts niet op state van een vorige test leunen.
    @Inject
    lateinit var cache: MockBerichtenCache

    // Echte factory (geen mock onder dit profiel): levert de REST-clients die met de
    // geconfigureerde read-timeout naar de WireMock-magazijns wijzen.
    @Inject
    lateinit var clientFactory: MagazijnClientFactory

    @BeforeEach
    fun setUp() {
        WireMockMagazijnResource.serverA!!.resetAll()
        WireMockMagazijnResource.serverB!!.resetAll()
        cache.clear()
    }

    @Test
    fun `succesvolle response met berichten van beide magazijnen`() {
        stubMagazijnSuccess(WireMockMagazijnResource.serverA!!, "magazijn-a")
        stubMagazijnSuccess(WireMockMagazijnResource.serverB!!, "magazijn-b")

        val response = given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("ophalen-gereed"))
        // Beide magazijnen leverden elk 1 bericht: volledig succes.
        assertTrue(response.contains("\"geslaagd\":2"), "Verwacht geslaagd:2 in: $response")
        assertTrue(response.contains("\"mislukt\":0"), "Verwacht mislukt:0 in: $response")
        assertTrue(response.contains("\"totaalMagazijnen\":2"), "Verwacht totaalMagazijnen:2 in: $response")
        assertTrue(response.contains("\"totaalBerichten\":2"), "Verwacht totaalBerichten:2 in: $response")

        // Beide berichten zijn gecached en opvraagbaar via GET /berichten.
        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten")
            .then().statusCode(200)
            .body("totalElements", `is`(2))
    }

    @Test
    fun `HTTP 500 van magazijn resulteert in FOUT status`() {
        WireMockMagazijnResource.serverA!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error"))
        )
        stubMagazijnSuccess(WireMockMagazijnResource.serverB!!, "magazijn-b")

        val response = given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("FOUT"))
        // Partial failure: magazijn-a (500) faalt, magazijn-b slaagt → degradatie i.p.v.
        // totale fout. De aggregatie telt 1 geslaagd / 1 mislukt en levert het ene
        // overlevende bericht.
        assertTrue(response.contains("\"geslaagd\":1"), "Verwacht geslaagd:1 in: $response")
        assertTrue(response.contains("\"mislukt\":1"), "Verwacht mislukt:1 in: $response")
        assertTrue(response.contains("\"totaalMagazijnen\":2"), "Verwacht totaalMagazijnen:2 in: $response")
        assertTrue(response.contains("\"totaalBerichten\":1"), "Verwacht totaalBerichten:1 in: $response")

        // Cruciaal: het bericht van het overlevende magazijn is wél gecached ondanks de fout
        // bij het andere magazijn.
        given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten")
            .then().statusCode(200)
            .body("totalElements", `is`(1))
    }

    @Test
    fun `query-timeout op de geconfigureerde grens levert TIMEOUT (niet de prod-default)`() {
        // De delay (3s) ligt boven de in WireMockTestProfile geconfigureerde query-timeout (2s)
        // maar onder de prod-default (10s). Zou de service de property negeren en de default
        // gebruiken, dan zou magazijn-a binnen 3s succesvol antwoorden en GEEN TIMEOUT geven.
        // Het verschijnen van TIMEOUT bewijst dus dat de geconfigureerde waarde gehonoreerd
        // wordt. TIMEOUT i.p.v. FOUT: FOUT is voorbehouden aan HTTP-errors / malformed responses.
        WireMockMagazijnResource.serverA!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(aResponse().withFixedDelay(3_000).withStatus(200).withBody("""{"berichten":[]}"""))
        )
        stubMagazijnSuccess(WireMockMagazijnResource.serverB!!, "magazijn-b")

        val response = given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)
            .extract().body().asString()

        assertTrue(
            response.contains("TIMEOUT"),
            "Verwacht TIMEOUT-event op de geconfigureerde 2s-grens maar stream bevatte: $response",
        )
    }

    @Test
    fun `read-timeout op de REST-client kapt een hangende magazijn-call af (socket-vangnet)`() {
        // Backstop-pad: de read-timeout (4000ms in profile) moet de socket vrijgeven als een
        // magazijn-call langer hangt dan de timeout, óók buiten de Mutiny ifNoItem om. We roepen
        // de client daarom DIRECT aan (geen query-timeout in dit pad) tegen een 6s-delay die de
        // read-timeout overschrijdt. Bewijst dat MagazijnClientFactory de timeout aandraait.
        WireMockMagazijnResource.serverA!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(aResponse().withFixedDelay(6_000).withStatus(200).withBody("""{"berichten":[]}"""))
        )

        val client = clientFactory.getAllClients()["magazijn-a"]

        assertNotNull(client, "magazijn-a client moet geconfigureerd zijn")

        val startNanos = System.nanoTime()

        val fout = assertThrows(Exception::class.java) {
            client!!.getBerichten(ontvanger, null)
        }

        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        // Tijdvenster i.p.v. exception-type-match (het concrete type verschilt per client-stack):
        // de ~4s read-timeout valt binnen [3000, 5500]. Een instant connect-fout (<100ms) of een
        // geslaagde call op de 6s-delay valt erbuiten, zodat de test alleen groen wordt als juist
        // de read-timeout vuurde — niet bij een willekeurige andere fout.
        assertTrue(
            elapsedMs in 3_000..5_500,
            "Read-timeout (4s) moet de call afkappen vóór de 6s-delay; duurde ${elapsedMs}ms, fout=${fout.javaClass.simpleName}",
        )
    }

    @Test
    fun `client-disconnect tijdens aggregatie stopt de cache-vulling niet`() {
        // De aggregatie loopt bewust door na een client-disconnect zodat de cache alsnog gevuld
        // raakt (een retry wordt dan een cache-hit). Magazijns antwoorden vertraagd (800ms, ruim
        // onder de 2s query-timeout) zodat er een venster is om te disconnecten vóór completion;
        // daarna pollen we GET /berichten tot de cache gevuld is.
        stubMagazijnSuccessDelayed(WireMockMagazijnResource.serverA!!, "magazijn-a", 800)
        stubMagazijnSuccessDelayed(WireMockMagazijnResource.serverB!!, "magazijn-b", 800)

        val httpClient = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(
            URI.create("http://localhost:${RestAssured.port}/api/v1/berichten/_ophalen"),
        ).header("X-Ontvanger", ontvanger).GET().build()

        // Ontvangen response-headers = server is begonnen met streamen = de aggregatie is
        // gesubscribed. Daarna cancelen we de subscription onmiddellijk: dat sluit de connectie
        // (disconnect) terwijl de magazijn-calls nog onderweg zijn.
        val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofPublisher())
            .get(5, TimeUnit.SECONDS)

        response.body().subscribe(object : Flow.Subscriber<List<ByteBuffer>> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.cancel()
            override fun onNext(item: List<ByteBuffer>) = Unit
            override fun onError(throwable: Throwable) = Unit
            override fun onComplete() = Unit
        })

        // De aggregatie rondt de magazijns rond 800ms af en vult de cache, ondanks de disconnect.
        // Tijdens het ophalen geeft GET /berichten 409 (ophalen-bezig); pas wanneer de
        // aggregatie de status op GEREED zet komt 200 met de gecachte berichten. Poll dus tot
        // 200 met data of tot een ruime deadline verstrijkt.
        val deadline = System.currentTimeMillis() + 6_000
        var totaal = 0
        var zagOphalenBezig = false

        while (System.currentTimeMillis() < deadline) {
            val resultaat = given()
                .header("X-Ontvanger", ontvanger)
                .`when`().get("/api/v1/berichten")
                .then().extract()

            if (resultaat.statusCode() == 409) zagOphalenBezig = true

            if (resultaat.statusCode() == 200) {
                totaal = resultaat.path<Int>("totalElements") ?: 0

                if (totaal >= 1) break
            }

            Thread.sleep(150)
        }

        // 409 ná de disconnect bewijst dat de aggregatie nog liep (de disconnect is dus
        // load-bearing, niet een race ná completion); totalElements>=1 bewijst dat ze alsnog
        // afrondde en de cache vulde. Beide samen pinnen "disconnect stopt de cache-vulling niet".
        assertTrue(
            zagOphalenBezig,
            "Verwacht minstens één 409 (ophalen-bezig) ná de disconnect — anders liep de aggregatie niet door",
        )
        assertTrue(
            totaal >= 1,
            "Cache moet alsnog gevuld raken nadat de client mid-aggregatie disconnect; totalElements=$totaal",
        )
    }

    @Test
    fun `malformed JSON response resulteert in FOUT status`() {
        WireMockMagazijnResource.serverA!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{invalid json}")
                )
        )
        stubMagazijnSuccess(WireMockMagazijnResource.serverB!!, "magazijn-b")

        val response = given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("FOUT"))
    }

    @Test
    fun `lege berichtenlijst van alle magazijnen`() {
        stubMagazijnEmpty(WireMockMagazijnResource.serverA!!)
        stubMagazijnEmpty(WireMockMagazijnResource.serverB!!)

        val response = given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("ophalen-gereed"))
        assertTrue(response.contains("\"totaalBerichten\":0"))
    }

    private fun stubMagazijnSuccess(server: com.github.tomakehurst.wiremock.WireMockServer, magazijnId: String) {
        server.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "berichten": [
                                    {
                                        "berichtId": "${java.util.UUID.randomUUID()}",
                                        "afzender": "00000001234567890000",
                                        "ontvanger": "$ontvangerWaarde",
                                        "onderwerp": "Test bericht van $magazijnId",
                                        "tijdstip": "2026-03-10T10:00:00Z",
                                        "magazijnId": "$magazijnId"
                                    }
                                ]
                            }
                        """.trimIndent())
                )
        )
    }

    private fun stubMagazijnSuccessDelayed(
        server: com.github.tomakehurst.wiremock.WireMockServer,
        magazijnId: String,
        delayMs: Int,
    ) {
        server.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(
                    aResponse()
                        .withFixedDelay(delayMs)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "berichten": [
                                    {
                                        "berichtId": "${java.util.UUID.randomUUID()}",
                                        "afzender": "00000001234567890000",
                                        "ontvanger": "$ontvangerWaarde",
                                        "onderwerp": "Test bericht van $magazijnId",
                                        "tijdstip": "2026-03-10T10:00:00Z",
                                        "magazijnId": "$magazijnId"
                                    }
                                ]
                            }
                        """.trimIndent())
                )
        )
    }

    private fun stubMagazijnEmpty(server: com.github.tomakehurst.wiremock.WireMockServer) {
        server.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"berichten":[]}""")
                )
        )
    }
}
