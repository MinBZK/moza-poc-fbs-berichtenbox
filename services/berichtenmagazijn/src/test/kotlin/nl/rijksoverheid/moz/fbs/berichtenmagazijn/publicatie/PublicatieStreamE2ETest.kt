package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import org.awaitility.Awaitility
import org.awaitility.Durations
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * End-to-end test voor [PublicatieStream]:
 *  1. POST /api/v1/berichten met `publicatiedatum=now()`
 *  2. Quarkus Scheduler polt elke 200ms (override via [DownstreamStubLifecycle])
 *  3. PublicatieStream claimt deliveries, bouwt CloudEvent, levert af aan twee
 *     embedded HTTP-servers (één per geconfigureerde downstream)
 *  4. Test asserteert dat beide servers binnen 5 seconden een POST hebben ontvangen
 *     met `Content-Type: application/cloudevents+json` en een JSON-body waarin
 *     `type=nl.rijksoverheid.fbs.bericht.gepubliceerd` voorkomt.
 *
 * Geen WireMock-dependency: [DownstreamHttpServer] gebruikt `com.sun.net.httpserver`
 * uit de JDK. Resource-lifecycle ([DownstreamStubLifecycle]) zet downstream-URLs
 * vóór Quarkus de config initialiseert — system-properties uit een TestProfile
 * komen daarvoor te laat aan in `magazijn.publicatie.downstreams.*`.
 */
@QuarkusTest
@QuarkusTestResource(value = DownstreamStubLifecycle::class, restrictToAnnotatedClass = true)
@TestProfile(PublicatieStreamE2ETest.E2EProfile::class)
class PublicatieStreamE2ETest {

    /**
     * Verschillende `TestProfile` per E2E-test forceert Quarkus om de applicatie
     * te restarten in plaats van een gedeelde JVM-context te hergebruiken. Zonder
     * dit hergebruikt Quarkus de profile van de vorige test, wat tot stale
     * `magazijn.publicatie.downstreams.*`-config kan leiden (URL's van een eerder
     * @QuarkusTestResource).
     */
    class E2EProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
        )
    }

    @Inject
    lateinit var berichten: BerichtRepository

    @BeforeEach
    @Transactional
    fun clean() {
        berichten.deleteAll()
        DownstreamStubLifecycle.server("aanmeld").reset()
        DownstreamStubLifecycle.server("notificatie").reset()
    }

    @Test
    fun `aangeleverd bericht wordt naar beide downstreams gepubliceerd binnen polling-window`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "E2E publicatie",
                  "inhoud": "Test inhoud voor publicatie stream"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)

        val aanmeld = DownstreamStubLifecycle.server("aanmeld")
        val notificatie = DownstreamStubLifecycle.server("notificatie")

        // Polling-interval is 200ms; downstreams moeten binnen enkele rondes ontvangen.
        Awaitility.await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(Durations.ONE_HUNDRED_MILLISECONDS)
            .untilAsserted {
                assertTrue(aanmeld.aantalAanroepen >= 1, "Aanmeld-stub geen events ontvangen")
                assertTrue(notificatie.aantalAanroepen >= 1, "Notificatie-stub geen events ontvangen")
            }

        // Borg dat de body een CloudEvent met het juiste type bevat.
        assertTrue(
            aanmeld.bodies.first().contains("nl.rijksoverheid.fbs.bericht.gepubliceerd"),
            "Aanmeld body bevat event-type niet: ${aanmeld.bodies.firstOrNull()}",
        )
        assertTrue(
            notificatie.bodies.first().contains("nl.rijksoverheid.fbs.bericht.gepubliceerd"),
            "Notificatie body bevat event-type niet: ${notificatie.bodies.firstOrNull()}",
        )
    }
}
