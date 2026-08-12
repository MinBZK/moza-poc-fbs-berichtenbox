package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie.MockProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.profiel.PartijResponse
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.hibernate.exception.ConstraintViolationException as HibernateConstraintViolationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * Verifieert dat exceptions in `skipOn` géén circuit in het aanleverpad openen, en dat de
 * aanleveraar in plaats daarvan de mapper-status ziet (400/409/403).
 * `requestVolumeThreshold=20, failureRatio=0.5` zou na 10 failures in 20 requests openen
 * als de exception WEL als failure telde; met skipOn moet het gesloten blijven.
 *
 * De eerste twee tests mocken de repository, zodat de fout uit de persistentielaag komt
 * en de `@CircuitBreaker`-interceptor op `slaBerichtOp` er echt langs moet. De derde test
 * gaat over `ToestemmingGeweigerdException`, die sinds de splitsing van
 * [BerichtOpslagService] in `valideerAanlevering` ontstaat — dat heeft zijn eigen circuit
 * met zijn eigen `skipOn`, dus die test bewaakt de skipOn-werking dáár, over de volle
 * keten tot en met de 403 die de aanleveraar ziet.
 *
 * [ValidatieCircuitBreakerTest] dekt hetzelfde circuit op bean-niveau en voegt de
 * tegenhanger toe: een Profiel-storing moet het circuit wél openen.
 */
@QuarkusTest
class CircuitBreakerSkipOnTest {

    @Inject
    @RestClient
    lateinit var profielClient: ProfielServiceClient

    @AfterEach
    fun resetProfielMock() {
        // De MockProfielServiceClient is een ApplicationScoped CDI-bean en wordt
        // hergebruikt tussen tests; zet de antwoordSupplier terug naar de default.
        (profielClient as MockProfielServiceClient).antwoordSupplier = { _, _ ->
            MockProfielServiceClient.defaultPartij(afzenderOin = "00000001003214345000")
        }
    }

    private fun validPayload() = """
        {
          "afzender": "00000001003214345000",
          "ontvanger": {"type": "BSN", "waarde": "999993653"},
          "onderwerp": "Test",
          "inhoud": "Test"
        }
    """.trimIndent()

    private fun installFailingRepository(throwable: Throwable) {
        val failingRepo = mockk<BerichtRepository>(relaxed = true)
        every { failingRepo.save(any<Bericht>()) } throws throwable
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

    @Test
    fun `ToestemmingGeweigerdException opent circuit niet`() {
        // ToestemmingGeweigerdException komt uit BerichtValidatieService, die in
        // valideerAanlevering draait — de methode met het Profiel-circuit. Staat de
        // exception niet in díé skipOn, dan slaan deze 30 requests na de twintigste om in
        // 503. Niet via de repo-mock dus, maar via de Profiel-Service-mock: een lege
        // PartijResponse (geen voorkeur) leidt tot weigering.
        (profielClient as MockProfielServiceClient).antwoordSupplier = { _, _ ->
            PartijResponse(voorkeuren = emptyList())
        }
        // Een aanleveraar die per ongeluk een loop start naar een ontvanger zonder
        // voorkeur mag het circuit niet openen — anders veroorzaakt één
        // misconfigureerde flow een DoS op alle legitieme aanleveraars.
        assertAllResponsesHaveStatus(expectedStatus = 403)
    }
}
