package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Multi
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.EventType
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockBerichtenCache
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

@QuarkusTest
@TestProfile(WireMockTestProfile::class)
@QuarkusTestResource(WireMockMagazijnResource::class)
class MagazijnClientWireMockTest {

    private val ontvanger = Bsn("999993653")
    // Raw identificatiewaarde voor gebruik in bericht-body's (Bericht.ontvanger slaat geen type-prefix op)
    private val ontvangerWaarde = "999993653"

    @Inject
    lateinit var sessiecache: Sessiecache

    // Gedeelde in-memory cache (zelfde bean over alle tests in deze klasse); per test legen
    // zodat GET /berichten-asserts niet op state van een vorige test leunen.
    @Inject
    internal lateinit var cache: MockBerichtenCache

    // Echte factory (geen mock onder dit profiel): levert de REST-clients die met de
    // geconfigureerde read-timeout naar de WireMock-magazijns wijzen.
    @Inject
    internal lateinit var clientFactory: MagazijnClientFactory

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

        val gereed = ophaalGereedEvent()

        // Beide magazijnen leverden elk 1 bericht: volledig succes.
        assertEquals(2, gereed.geslaagd)
        assertEquals(0, gereed.mislukt)
        assertEquals(2, gereed.totaalMagazijnen)
        assertEquals(2, gereed.totaalBerichten)

        // Beide berichten zijn gecached en opvraagbaar via de facade.
        assertEquals(2L, sessiecache.lijst(ontvanger).totalElements)
    }

    @Test
    fun `HTTP 500 van magazijn resulteert in FOUT status`() {
        WireMockMagazijnResource.serverA!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error"))
        )
        stubMagazijnSuccess(WireMockMagazijnResource.serverB!!, "magazijn-b")

        val events = ophaalEvents()

        assertTrue(events.any { it.status == MagazijnStatus.FOUT }, "Verwacht FOUT-event in: $events")
        // Partial failure: magazijn-a (500) faalt, magazijn-b slaagt → degradatie i.p.v.
        // totale fout. De aggregatie telt 1 geslaagd / 1 mislukt en levert het ene
        // overlevende bericht.
        val gereed = gereedEvent(events)

        assertEquals(1, gereed.geslaagd)
        assertEquals(1, gereed.mislukt)
        assertEquals(2, gereed.totaalMagazijnen)
        assertEquals(1, gereed.totaalBerichten)

        // Cruciaal: het bericht van het overlevende magazijn is wél gecached ondanks de fout
        // bij het andere magazijn.
        assertEquals(1L, sessiecache.lijst(ontvanger).totalElements)
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

        val events = ophaalEvents()

        assertTrue(
            events.any { it.status == MagazijnStatus.TIMEOUT },
            "Verwacht TIMEOUT-event op de geconfigureerde 2s-grens maar stream bevatte: $events",
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
            client!!.getBerichten(ontvanger.toCanonicalString(), null)
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
        // De aggregatie loopt bewust door na een client-disconnect zodat de cache alsnog
        // gevuld raakt (een retry wordt dan een cache-hit). We spiegelen het SSE-resource-
        // patroon van de consumer: een buitenste emitter observeert de aggregatie-Multi
        // zonder cancel door te geven. De buitenste subscriptie wordt direct gecanceld
        // (de "disconnect") terwijl de magazijn-calls (800ms delay, ruim onder de 2s
        // query-timeout) nog onderweg zijn; daarna pollen we de facade tot de cache gevuld is.
        stubMagazijnSuccessDelayed(WireMockMagazijnResource.serverA!!, "magazijn-a", 800)
        stubMagazijnSuccessDelayed(WireMockMagazijnResource.serverB!!, "magazijn-b", 800)

        val aggregation = sessiecache.ophalen(ontvanger)
        val buitenste = Multi.createFrom().emitter<MagazijnEvent> { emitter ->
            aggregation.subscribe().with(
                { event -> if (!emitter.isCancelled) emitter.emit(event) },
                { fout -> if (!emitter.isCancelled) emitter.fail(fout) },
                { if (!emitter.isCancelled) emitter.complete() },
            )
        }

        buitenste.subscribe().with({ }, { }, { }).cancel()

        // De aggregatie rondt de magazijns rond 800ms af en vult de cache, ondanks de
        // disconnect. Tijdens het ophalen gooit de facade 409 (ophalen-bezig); pas wanneer
        // de aggregatie de status op GEREED zet komt de lijst terug. Poll dus tot data of
        // tot een ruime deadline verstrijkt.
        val deadline = System.currentTimeMillis() + 6_000
        var totaal = 0L
        var zagOphalenBezig = false

        while (System.currentTimeMillis() < deadline) {
            try {
                totaal = sessiecache.lijst(ontvanger).totalElements

                if (totaal >= 1) break
            } catch (e: WebApplicationException) {
                if (e.response.status == 409) zagOphalenBezig = true
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

        val events = ophaalEvents()

        assertTrue(events.any { it.status == MagazijnStatus.FOUT }, "Verwacht FOUT-event in: $events")
    }

    @Test
    fun `lege berichtenlijst van alle magazijnen`() {
        stubMagazijnEmpty(WireMockMagazijnResource.serverA!!)
        stubMagazijnEmpty(WireMockMagazijnResource.serverB!!)

        val gereed = ophaalGereedEvent()

        assertEquals(0, gereed.totaalBerichten)
    }

    private fun ophaalEvents(): List<MagazijnEvent> =
        sessiecache.ophalen(ontvanger).collect().asList().await().atMost(Duration.ofSeconds(15))

    private fun gereedEvent(events: List<MagazijnEvent>): MagazijnEvent =
        requireNotNull(events.lastOrNull { it.event == EventType.OPHALEN_GEREED }) {
            "Verwacht OPHALEN_GEREED-event in: $events"
        }

    private fun ophaalGereedEvent(): MagazijnEvent = gereedEvent(ophaalEvents())

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
                                        "ontvanger": { "type": "BSN", "waarde": "$ontvangerWaarde" },
                                        "onderwerp": "Test bericht van $magazijnId",
                                        "inhoud": "Inhoud van $magazijnId",
                                        "publicatietijdstip": "2026-03-10T10:00:00Z",
                                        "magazijnId": "$magazijnId",
                                        "aantalBijlagen": 0
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
                                        "ontvanger": { "type": "BSN", "waarde": "$ontvangerWaarde" },
                                        "onderwerp": "Test bericht van $magazijnId",
                                        "inhoud": "Inhoud van $magazijnId",
                                        "publicatietijdstip": "2026-03-10T10:00:00Z",
                                        "magazijnId": "$magazijnId",
                                        "aantalBijlagen": 0
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
