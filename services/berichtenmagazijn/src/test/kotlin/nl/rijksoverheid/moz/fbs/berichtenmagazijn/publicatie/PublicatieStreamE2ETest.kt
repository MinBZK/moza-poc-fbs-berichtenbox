package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import org.awaitility.Awaitility
import org.awaitility.Durations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * End-to-end-tests voor [PublicatieStream], over de volle keten:
 *  1. POST /api/v1/berichten met `publicatietijdstip=now()`
 *  2. Quarkus Scheduler polt elke ronde: 200ms geconfigureerd via [DownstreamStubLifecycle],
 *     door Quarkus geclampt naar 1s
 *  3. PublicatieStream claimt deliveries, bouwt CloudEvents en levert af aan de twee
 *     embedded HTTP-servers uit [DownstreamStubLifecycle]
 *  4. De test asserteert wat er bij de stubs en in de delivery-rijen terechtkomt
 *
 * Geen WireMock-dependency: [DownstreamHttpServer] gebruikt `com.sun.net.httpserver` uit de
 * JDK. De resource-lifecycle zet downstream-URLs vóór Quarkus de config initialiseert —
 * system-properties uit een TestProfile komen daarvoor te laat aan in
 * `magazijn.publicatie.downstreams.*`.
 *
 * De vier paden (aflevering, 4xx-terminal, 5xx-uitputting, retry) staan bewust in één klasse.
 * Een class-scoped test-resource dwingt per testklasse een eigen applicatie-instantie mét eigen
 * database-container af; als losse klassen kostten deze vier tests vier starts terwijl ze alleen
 * verschillen in wat de aanmeld-stub antwoordt. Dat gedrag zet elke test nu zelf, op de gedeelde
 * server.
 */
@QuarkusTest
@QuarkusTestResource(value = DownstreamStubLifecycle::class, restrictToAnnotatedClass = true)
class PublicatieStreamE2ETest {

    @Inject
    lateinit var berichten: BerichtRepository

    @Inject
    lateinit var deliveries: PublicatieDeliveryRepository

    private val aanmeld: DownstreamHttpServer
        get() = DownstreamStubLifecycle.server("aanmeld")

    private val notificatie: DownstreamHttpServer
        get() = DownstreamStubLifecycle.server("notificatie")

    /**
     * Verwijderen vóór resetten, en beide in dezelfde transactie: de claim-transactie houdt de
     * delivery-rij gelockt zolang de HTTP-call loopt, dus blokkeert `deleteAll` tot een lopende
     * levering klaar is. Zo landt geen enkele call van de vorige test ná de reset — die zou de
     * call-nummering verschuiven en bij de retry-test twee verschillende berichten vergelijken.
     */
    @BeforeEach
    @Transactional
    fun clean() {
        deliveries.deleteAll()
        berichten.deleteAll()
        aanmeld.reset()
        notificatie.reset()
    }

    @Test
    fun `aangeleverd bericht wordt naar beide downstreams gepubliceerd binnen polling-window`() {
        lever(onderwerp = "E2E publicatie", inhoud = "Test inhoud voor publicatie stream")

        Awaitility.await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(Durations.ONE_HUNDRED_MILLISECONDS)
            .untilAsserted {
                assertTrue(aanmeld.aantalAanroepen >= 1, "Aanmeld-stub geen events ontvangen")
                assertTrue(notificatie.aantalAanroepen >= 1, "Notificatie-stub geen events ontvangen")
            }

        assertTrue(
            aanmeld.bodies.first().contains("nl.rijksoverheid.fbs.bericht.gepubliceerd"),
            "Aanmeld body bevat event-type niet: ${aanmeld.bodies.firstOrNull()}",
        )
        assertTrue(
            notificatie.bodies.first().contains("nl.rijksoverheid.fbs.bericht.gepubliceerd"),
            "Notificatie body bevat event-type niet: ${notificatie.bodies.firstOrNull()}",
        )
    }

    /**
     * Client-fout (400) is niet-herstelbaar, dus de delivery moet meteen `MISLUKT` worden na
     * exact 1 poging — geen retry.
     *
     * **Niet `volgende_poging IS NULL`**: [PublicatieDeliveryEntity.markeerMislukt] houdt de
     * oude waarde aan bij terminal MISLUKT (kolom `nullable = false`, sentinel-design). Status
     * `MISLUKT` is daarom de terminale-marker, niet de volgende_poging-tijd.
     */
    @Test
    fun `400 op Aanmeld leidt tot MISLUKT na 1 poging zonder retry`() {
        aanmeld.statusVoorAanroep = { _ -> 400 }

        lever(onderwerp = "4xx-pad", inhoud = "Inhoud")

        // Wacht tot de aanmeld-stub geraakt is en de delivery terminal MISLUKT is.
        Awaitility.await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(Durations.ONE_HUNDRED_MILLISECONDS)
            .untilAsserted {
                assertTrue(
                    aanmeld.aantalAanroepen >= 1,
                    "verwacht >= 1 call op Aanmeld",
                )

                val rijen = transactioneelOphalen()
                val aanmeldRij = rijen.firstOrNull { it.doel == "aanmeld" }
                    ?: error("aanmeld-delivery niet gevonden in rijen=${rijen.map { it.doel }}")

                assertEquals(DeliveryStatus.MISLUKT, aanmeldRij.status, "status moet MISLUKT zijn")
                assertEquals(1, aanmeldRij.pogingen, "geen retry: pogingen moet 1 zijn na enkele 400")
            }

        // `during(2s)` eist dat de assertie twee polling-rondes lang blijft slagen, niet
        // alleen op één meetmoment: een late retry zou anders na de meting kunnen komen.
        val callsNaTerminal = aanmeld.aantalAanroepen

        Awaitility.await()
            .during(2, TimeUnit.SECONDS)
            .atMost(3, TimeUnit.SECONDS)
            .pollInterval(Durations.ONE_HUNDRED_MILLISECONDS)
            .untilAsserted {
                assertEquals(
                    callsNaTerminal,
                    aanmeld.aantalAanroepen,
                    "aanmeld mag na MISLUKT geen extra calls meer krijgen",
                )

                val aanmeldRij = transactioneelOphalen().firstOrNull { it.doel == "aanmeld" }
                    ?: error("aanmeld-delivery weg")

                // Als de scheduler hier ondanks MISLUKT-status toch zou claimen +
                // retryen, zou pogingen >= 2 worden — afgevangen ook als stub-call-
                // count flaky is door clamp-timing.
                assertEquals(1, aanmeldRij.pogingen, "MISLUKT-rij mag niet opnieuw geprobeerd zijn")
                assertEquals(DeliveryStatus.MISLUKT, aanmeldRij.status, "status moet MISLUKT blijven")
            }
    }

    /**
     * Een downstream die blijft falen (500) moet worden geretryd tot het budget uit
     * [DownstreamStubLifecycle.MAX_POGINGEN] en daarna terminal `MISLUKT` worden — niet
     * eindeloos doorgaan. Borgt de integratie van "herstelbaar maar uitgeput → terminal", die
     * los alleen op unit-niveau ([RetryBeleidTest], [PublicatieClaimVerwerkerEdgeCaseTest])
     * gedekt was.
     */
    @Test
    fun `aanhoudende 500 leidt tot MISLUKT na maxPogingen retries`() {
        assertTrue(
            DownstreamStubLifecycle.MAX_POGINGEN > 1,
            "het budget moet ruimte laten voor minstens één retry, anders meet deze test hetzelfde als het 4xx-pad",
        )

        aanmeld.statusVoorAanroep = { _ -> 500 }

        lever(onderwerp = "5xx-pad", inhoud = "Inhoud")

        Awaitility.await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(Durations.ONE_HUNDRED_MILLISECONDS)
            .untilAsserted {
                val aanmeldRij = transactioneelOphalen().firstOrNull { it.doel == "aanmeld" }
                    ?: error("aanmeld-delivery niet gevonden")

                assertEquals(DeliveryStatus.MISLUKT, aanmeldRij.status, "status moet MISLUKT zijn na uitputting")
                assertEquals(
                    DownstreamStubLifecycle.MAX_POGINGEN,
                    aanmeldRij.pogingen,
                    "moet exact maxPogingen pogingen hebben gedaan",
                )
                assertEquals(
                    DownstreamStubLifecycle.MAX_POGINGEN,
                    aanmeld.aantalAanroepen,
                    "elke poging hoort een echte HTTP-call te zijn; een afwijking wijst op een lek uit een vorige test",
                )
            }

        // Een terminale status sluit re-claim uit, dus er mag niets meer bijkomen.
        val callsNaTerminal = aanmeld.aantalAanroepen

        Awaitility.await()
            .during(2, TimeUnit.SECONDS)
            .atMost(3, TimeUnit.SECONDS)
            .pollInterval(Durations.ONE_HUNDRED_MILLISECONDS)
            .untilAsserted {
                assertTrue(
                    aanmeld.aantalAanroepen <= callsNaTerminal,
                    "aanmeld mag na MISLUKT geen extra calls meer krijgen",
                )

                val aanmeldRij = transactioneelOphalen().firstOrNull { it.doel == "aanmeld" }
                    ?: error("aanmeld-delivery weg")

                assertEquals(
                    DownstreamStubLifecycle.MAX_POGINGEN,
                    aanmeldRij.pogingen,
                    "MISLUKT-rij mag niet opnieuw geprobeerd zijn",
                )
                assertEquals(DeliveryStatus.MISLUKT, aanmeldRij.status, "status moet MISLUKT blijven")
            }
    }

    /**
     * Retry-pad: eerste delivery aan Aanmeld faalt (HTTP 500), tweede slaagt. Borgt:
     *  - [DownstreamClient] mapt non-2xx naar [DownstreamResultaat.Mislukt]
     *  - [PublicatieStream] roept [PublicatieClaimer.markeerMislukt] met een berekende
     *    `volgende_poging` ([RetryBeleid.volgendePoging])
     *  - Bij volgende pollronde wordt de delivery opnieuw geclaimd en succesvol afgeleverd
     *  - Notificatie-stub krijgt onafhankelijk gewoon één event (per-downstream isolatie)
     */
    @Test
    fun `eerste 500 op Aanmeld leidt tot retry en tweede poging slaagt`() {
        aanmeld.statusVoorAanroep = { poging -> if (poging == 1) 500 else 202 }

        lever(onderwerp = "Retry-pad", inhoud = "Inhoud")

        // De retry-backoff (basis 50ms) valt binnen één polling-ronde, dus de tweede
        // aflevering hoort ruim binnen het venster te vallen.
        Awaitility.await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(Durations.ONE_HUNDRED_MILLISECONDS)
            .untilAsserted {
                assertTrue(
                    aanmeld.aantalAanroepen >= 2,
                    "verwacht >= 2 calls op Aanmeld (eerste 500, tweede 202), kreeg ${aanmeld.aantalAanroepen}",
                )
                assertTrue(
                    notificatie.aantalAanroepen >= 1,
                    "verwacht >= 1 call op Notificatie",
                )
            }

        // Beide pogingen moeten dezelfde CloudEvent-`id` hebben (deterministisch per
        // (berichtId, doel)) — andere attributen mogen verschillen (`time` is per attempt).
        val bodies = aanmeld.bodies
        val idRegex = Regex("\"id\":\"([^\"]+)\"")
        val firstId = idRegex.find(bodies.first())?.groupValues?.get(1)
        val secondId = idRegex.find(bodies[1])?.groupValues?.get(1)

        assertEquals(firstId, secondId, "retry moet identieke CloudEvent-id hebben (deterministisch)")
    }

    private fun lever(onderwerp: String, inhoud: String) {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "$onderwerp",
                  "inhoud": "$inhoud"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
    }

    private fun transactioneelOphalen(): List<PublicatieDeliveryEntity> =
        QuarkusTransaction.requiringNew().call {
            deliveries.listAll()
        }
}
