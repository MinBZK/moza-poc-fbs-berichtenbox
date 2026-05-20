package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
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
 * E2E voor het retry-pad van [PublicatieStream]: eerste delivery aan Aanmeld
 * faalt (HTTP 500), tweede slaagt. Borgt:
 *  - [DownstreamClient] mapt non-2xx naar [DownstreamResultaat.Mislukt]
 *  - [PublicatieStream] roept [PublicatieClaimer.markeerMislukt] met een berekende
 *    `volgende_poging` ([RetryBeleid.volgendePoging])
 *  - Bij volgende pollronde wordt de delivery opnieuw geclaimd en succesvol afgeleverd
 *  - Notificatie-stub krijgt onafhankelijk gewoon één event (per-downstream isolatie)
 */
@QuarkusTest
@QuarkusTestResource(
    value = PublicatieStreamRetryE2ETest.RetryStubLifecycle::class,
    restrictToAnnotatedClass = true,
)
class PublicatieStreamRetryE2ETest {

    @Inject
    lateinit var berichten: BerichtRepository

    @BeforeEach
    @Transactional
    fun clean() {
        berichten.deleteAll()
        RetryStubLifecycle.aanmeld.reset()
        RetryStubLifecycle.notificatie.reset()
    }

    @Test
    fun `eerste 500 op Aanmeld leidt tot retry en tweede poging slaagt`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Retry-pad",
                  "inhoud": "Inhoud"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)

        // Aanmeld faalt eenmalig (500), daarna 202. Notificatie altijd 202.
        // Polling-interval is 1s (Quarkus clampt sub-1s naar 1s); retry-backoff
        // (basis 50ms) voegt nog ~100ms toe. Verwacht binnen 15s een succesvolle
        // tweede aflevering.
        Awaitility.await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(Durations.ONE_HUNDRED_MILLISECONDS)
            .untilAsserted {
                // Aanmeld minstens 2 calls (1 fout + 1 succes), Notificatie 1.
                assertTrue(
                    RetryStubLifecycle.aanmeld.aantalAanroepen >= 2,
                    "verwacht >= 2 calls op Aanmeld (eerste 500, tweede 202), kreeg ${RetryStubLifecycle.aanmeld.aantalAanroepen}",
                )
                assertTrue(
                    RetryStubLifecycle.notificatie.aantalAanroepen >= 1,
                    "verwacht >= 1 call op Notificatie",
                )
            }

        // Beide pogingen moeten dezelfde CloudEvent-`id` hebben (deterministisch per
        // (berichtId, doel)) — andere attributen mogen verschillen (`time` is per attempt).
        val bodies = RetryStubLifecycle.aanmeld.bodies
        val idRegex = Regex("\"id\":\"([^\"]+)\"")
        val firstId = idRegex.find(bodies.first())?.groupValues?.get(1)
        val secondId = idRegex.find(bodies[1])?.groupValues?.get(1)
        assertEquals(firstId, secondId, "retry moet identieke CloudEvent-id hebben (deterministisch)")
    }

    /**
     * Test-resource: Aanmeld-stub geeft één keer 500, daarna 202; Notificatie-stub
     * altijd 202.
     */
    class RetryStubLifecycle : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            aanmeld.start()
            notificatie.start()
            return mapOf(
                "magazijn.publicatie.downstreams.aanmeld.url" to aanmeld.baseUrl,
                "magazijn.publicatie.downstreams.notificatie.url" to notificatie.baseUrl,
                // Polling-interval 200ms — Quarkus clampt naar 1s, snelste optie.
                "magazijn.publicatie.polling.interval" to "200ms",
                // Lage backoff per-downstream zodat retry binnen test-window valt.
                "magazijn.publicatie.downstreams.aanmeld.backoff.basis" to "PT0.05S",
                "magazijn.publicatie.downstreams.aanmeld.max-pogingen" to "3",
                "magazijn.publicatie.downstreams.notificatie.backoff.basis" to "PT0.05S",
                "magazijn.publicatie.downstreams.notificatie.max-pogingen" to "3",
                "quarkus.scheduler.enabled" to "true",
            )
        }

        override fun stop() {
            aanmeld.close()
            notificatie.close()
        }

        companion object {
            val aanmeld = DownstreamHttpServer(
                statusVoorAanroep = { poging -> if (poging == 1) 500 else 202 },
            )
            val notificatie = DownstreamHttpServer()
        }
    }
}
