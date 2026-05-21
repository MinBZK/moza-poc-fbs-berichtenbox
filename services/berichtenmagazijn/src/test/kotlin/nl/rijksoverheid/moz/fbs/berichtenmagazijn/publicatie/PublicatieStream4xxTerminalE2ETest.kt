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
 * E2E voor het 4xx-pad: client-fout (400) is niet-herstelbaar, dus delivery
 * moet meteen `MISLUKT` worden na exact 1 poging — geen retry. Borgt dat dit
 * pad end-to-end gedekt is (eerder alleen unit/branch-niveau).
 *
 * Tegenhanger van [PublicatieStreamRetryE2ETest] die juist 5xx + retry test.
 *
 * **Niet `volgende_poging IS NULL`**: [PublicatieDeliveryEntity.markeerMislukt]
 * houdt de oude waarde aan bij terminal MISLUKT (kolom `nullable = false`,
 * sentinel-design). Status `MISLUKT` is daarom de terminale-marker, niet de
 * volgende_poging-tijd.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PublicatieStream4xxTerminalE2ETest.Stub400Lifecycle::class,
    restrictToAnnotatedClass = true,
)
class PublicatieStream4xxTerminalE2ETest {

    @Inject
    lateinit var berichten: BerichtRepository

    @Inject
    lateinit var deliveries: PublicatieDeliveryRepository

    @BeforeEach
    @Transactional
    fun clean() {
        deliveries.deleteAll()
        berichten.deleteAll()
        Stub400Lifecycle.aanmeld.reset()
    }

    @Test
    fun `400 op Aanmeld leidt tot MISLUKT na 1 poging zonder retry`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "4xx-pad",
                  "inhoud": "Inhoud"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)

        // Wacht tot de aanmeld-stub minstens 1× geraakt is en de delivery
        // terminal MISLUKT is. Polling-interval 200ms (Quarkus clampt naar 1s).
        Awaitility.await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(Durations.ONE_HUNDRED_MILLISECONDS)
            .untilAsserted {
                assertTrue(
                    Stub400Lifecycle.aanmeld.aantalAanroepen >= 1,
                    "verwacht >= 1 call op Aanmeld",
                )
                val rijen = transactioneelOphalen()
                val aanmeldRij = rijen.firstOrNull { it.doel == "aanmeld" }
                    ?: error("aanmeld-delivery niet gevonden in rijen=${rijen.map { it.doel }}")
                assertEquals(DeliveryStatus.MISLUKT, aanmeldRij.status, "status moet MISLUKT zijn")
                assertEquals(1, aanmeldRij.pogingen, "geen retry: pogingen moet 1 zijn na enkele 400")
            }

        // Defense-in-depth tegen scheduler-clamp-races: `during(2s).atMost(3s)`
        // verifieert dat de assertie 2 seconden lang ONONDERBROKEN blijft slagen.
        // Een failure halverwege fail't de test direct (Awaitility short-circuit
        // op de eerste mismatch — sneller dan blocking sleep). Bewijst dus
        // "geen retry op 4xx" gedurende ~2 polling-intervals.
        val callsNaTerminal = Stub400Lifecycle.aanmeld.aantalAanroepen
        Awaitility.await()
            .during(2, TimeUnit.SECONDS)
            .atMost(3, TimeUnit.SECONDS)
            .pollInterval(Durations.ONE_HUNDRED_MILLISECONDS)
            .untilAsserted {
                assertEquals(
                    callsNaTerminal,
                    Stub400Lifecycle.aanmeld.aantalAanroepen,
                    "aanmeld mag na MISLUKT geen extra calls meer krijgen",
                )
                val rijen = transactioneelOphalen()
                val aanmeldRij = rijen.firstOrNull { it.doel == "aanmeld" }
                    ?: error("aanmeld-delivery weg")
                // Als de scheduler hier ondanks MISLUKT-status toch zou claimen +
                // retryen, zou pogingen >= 2 worden — afgevangen ook als stub-call-
                // count flaky is door clamp-timing.
                assertEquals(1, aanmeldRij.pogingen, "MISLUKT-rij mag niet opnieuw geprobeerd zijn")
                assertEquals(DeliveryStatus.MISLUKT, aanmeldRij.status, "status moet MISLUKT blijven")
            }
    }

    private fun transactioneelOphalen(): List<PublicatieDeliveryEntity> =
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call {
            deliveries.listAll()
        }

    /** Aanmeld-stub geeft altijd 400 (niet-herstelbaar). */
    class Stub400Lifecycle : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            aanmeld.start()
            return mapOf(
                "magazijn.publicatie.downstreams.aanmeld.url" to aanmeld.baseUrl,
                "magazijn.publicatie.polling.interval" to "200ms",
                "magazijn.publicatie.downstreams.aanmeld.backoff.basis" to "PT0.05S",
                "magazijn.publicatie.downstreams.aanmeld.max-pogingen" to "3",
                "quarkus.scheduler.enabled" to "true",
            )
        }

        override fun stop() {
            aanmeld.close()
        }

        companion object {
            val aanmeld = DownstreamHttpServer(statusVoorAanroep = { _ -> 400 })
        }
    }
}
