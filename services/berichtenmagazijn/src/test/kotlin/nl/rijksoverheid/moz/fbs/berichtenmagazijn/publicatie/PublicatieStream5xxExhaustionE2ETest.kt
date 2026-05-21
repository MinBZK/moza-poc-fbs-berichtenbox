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
 * E2E voor het herstelbare-5xx-pad: een downstream die blijft falen (500) moet
 * worden geretryd tot `maxPogingen` en daarna terminal `MISLUKT` worden — niet
 * eindeloos doorgaan. Tegenhanger van [PublicatieStream4xxTerminalE2ETest]
 * (meteen-terminal) en [PublicatieStreamRetryE2ETest] (faal-dan-succes).
 *
 * Borgt de integratie van "herstelbaar maar uitgeput → terminal", die los
 * alleen op unit-niveau ([RetryBeleidTest], [PublicatieClaimVerwerkerEdgeCaseTest])
 * gedekt was. `max-pogingen=2` houdt de test snel.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PublicatieStream5xxExhaustionE2ETest.Stub500Lifecycle::class,
    restrictToAnnotatedClass = true,
)
class PublicatieStream5xxExhaustionE2ETest {

    @Inject
    lateinit var berichten: BerichtRepository

    @Inject
    lateinit var deliveries: PublicatieDeliveryRepository

    @BeforeEach
    @Transactional
    fun clean() {
        deliveries.deleteAll()
        berichten.deleteAll()
        Stub500Lifecycle.aanmeld.reset()
    }

    @Test
    fun `aanhoudende 500 leidt tot MISLUKT na maxPogingen retries`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "5xx-pad",
                  "inhoud": "Inhoud"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)

        // 500 is herstelbaar → retry tot maxPogingen (2) bereikt → terminal MISLUKT.
        Awaitility.await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(Durations.ONE_HUNDRED_MILLISECONDS)
            .untilAsserted {
                val aanmeldRij = transactioneelOphalen().firstOrNull { it.doel == "aanmeld" }
                    ?: error("aanmeld-delivery niet gevonden")
                assertEquals(DeliveryStatus.MISLUKT, aanmeldRij.status, "status moet MISLUKT zijn na uitputting")
                assertEquals(2, aanmeldRij.pogingen, "moet exact maxPogingen (2) pogingen hebben gedaan")
            }

        // Na terminal MISLUKT: geen verdere claims/calls meer (status sluit re-claim uit).
        val callsNaTerminal = Stub500Lifecycle.aanmeld.aantalAanroepen
        Awaitility.await()
            .during(2, TimeUnit.SECONDS)
            .atMost(3, TimeUnit.SECONDS)
            .pollInterval(Durations.ONE_HUNDRED_MILLISECONDS)
            .untilAsserted {
                assertTrue(
                    Stub500Lifecycle.aanmeld.aantalAanroepen <= callsNaTerminal,
                    "aanmeld mag na MISLUKT geen extra calls meer krijgen",
                )
                val aanmeldRij = transactioneelOphalen().firstOrNull { it.doel == "aanmeld" }
                    ?: error("aanmeld-delivery weg")
                assertEquals(2, aanmeldRij.pogingen, "MISLUKT-rij mag niet opnieuw geprobeerd zijn")
                assertEquals(DeliveryStatus.MISLUKT, aanmeldRij.status, "status moet MISLUKT blijven")
            }
    }

    private fun transactioneelOphalen(): List<PublicatieDeliveryEntity> =
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call {
            deliveries.listAll()
        }

    /** Aanmeld-stub geeft altijd 500 (herstelbaar); max-pogingen=2 begrenst de retries. */
    class Stub500Lifecycle : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            aanmeld.start()
            return mapOf(
                "magazijn.publicatie.downstreams.aanmeld.url" to aanmeld.baseUrl,
                "magazijn.publicatie.polling.interval" to "200ms",
                "magazijn.publicatie.downstreams.aanmeld.backoff.basis" to "PT0.05S",
                "magazijn.publicatie.downstreams.aanmeld.max-pogingen" to "2",
                "quarkus.scheduler.enabled" to "true",
            )
        }

        override fun stop() {
            aanmeld.close()
        }

        companion object {
            val aanmeld = DownstreamHttpServer(statusVoorAanroep = { _ -> 500 })
        }
    }
}
