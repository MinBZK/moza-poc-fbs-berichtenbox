package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.common.DomainValidationException
import org.hibernate.exception.ConstraintViolationException as HibernateConstraintViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * Verifieert dat exceptions in `skipOn` (client-fouten) het circuit NIET openen.
 * `requestVolumeThreshold=20, failureRatio=0.5` zou na 10 failures in 20 requests
 * openen als de exception WEL als failure telde; met skipOn moet het gesloten blijven.
 *
 * Mockt de repository zodat de echte `@CircuitBreaker`-interceptor actief blijft
 * tijdens de test — dat is precies het gedrag dat we willen verifiëren.
 */
@QuarkusTest
class CircuitBreakerSkipOnTest {

    private fun validPayload() = """
        {
          "afzender": "00000001003214345000",
          "ontvanger": "999993653",
          "onderwerp": "Test",
          "inhoud": "Test"
        }
    """.trimIndent()

    private fun installFailingRepository(throwable: Throwable) {
        val failingRepo = mockk<BerichtRepository>(relaxed = true)
        every { failingRepo.opslaan(any<Bericht>()) } throws throwable
        QuarkusMock.installMockForType(failingRepo, BerichtRepository::class.java)
    }

    private fun assertAllResponsesHaveStatus(expectedStatus: Int) {
        // 30 requests: ruim boven requestVolumeThreshold=20. Als breaker zou openen,
        // zouden we 503 zien i.p.v. de mapper-status.
        repeat(30) { i ->
            val actual = given()
                .contentType(ContentType.JSON)
                .body(validPayload())
                .`when`().post("/api/v1/berichten")
                .then()
                .extract().statusCode()
            assertEquals(expectedStatus, actual, "request $i zou $expectedStatus moeten zijn")
        }
    }

    @Test
    fun `DomainValidationException opent circuit niet`() {
        installFailingRepository(DomainValidationException("test-failure"))
        // DomainValidationException zit expliciet in skipOn → 30x 400, geen 503.
        assertAllResponsesHaveStatus(expectedStatus = 400)
    }

    @Test
    fun `Hibernate ConstraintViolationException opent circuit niet`() {
        installFailingRepository(
            HibernateConstraintViolationException(
                "duplicate", SQLException("dup", "23505"), "uq_test",
            ),
        )
        // Hibernate ConstraintViolationException zit in skipOn → 30x 409, geen 503.
        assertAllResponsesHaveStatus(expectedStatus = 409)
    }
}
