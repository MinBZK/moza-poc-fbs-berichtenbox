package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.persistence.PersistenceException
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bewijst dat het circuit daadwerkelijk opent zodra de service herhaaldelijk faalt
 * met een exception die níét in `skipOn` zit. Zonder deze test zou een per ongeluk
 * toegevoegde exception-type in skipOn, of een verhoging van `requestVolumeThreshold`
 * / `failureRatio`, ongemerkt de breaker effectief uitzetten.
 *
 * Thresholds uit @CircuitBreaker-annotatie: requestVolumeThreshold=20, failureRatio=0.5.
 * Dus na 10+ fails in de eerste 20 requests moet het circuit open zijn en minstens
 * één volgende request 503 retourneren.
 *
 * Draait in een apart Quarkus-profile zodat de open-circuit-state niet doorlekt naar
 * andere tests in dezelfde suite (circuit-state is application-scoped en blijft open
 * gedurende de `delay` van 5 seconden na trip).
 */
@QuarkusTest
@TestProfile(CircuitBreakerTripTest.Profile::class)
class CircuitBreakerTripTest {

    class Profile : QuarkusTestProfile

    private fun payload() = """
        {
          "afzender": "00000001003214345000",
          "ontvanger": {"type": "BSN", "waarde": "999993653"},
          "onderwerp": "Test",
          "inhoud": "Test"
        }
    """.trimIndent()

    @Test
    fun `PersistenceException opent circuit na threshold en volgende requests krijgen 503`() {
        val failingRepo = mockk<BerichtRepository>(relaxed = true)
        every { failingRepo.opslaan(any<Bericht>()) } throws PersistenceException("infra fout")
        QuarkusMock.installMockForType(failingRepo, BerichtRepository::class.java)

        val statusses = (1..30).map {
            given()
                .contentType(ContentType.JSON)
                .body(payload())
                .`when`().post("/api/v1/berichten")
                .then()
                .extract().statusCode()
        }

        val aantal503 = statusses.count { it == 503 }
        assertTrue(
            aantal503 > 0,
            "Circuit breaker moet openen na herhaalde fouten; aantal 503 = $aantal503, reeks = $statusses",
        )
    }
}
