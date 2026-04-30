package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.mockk.mockk
import io.restassured.http.ContentType
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.IdentificatienummerType
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Contracttest voor 503 Problem-response wanneer de circuit breaker open is.
 * Vervangt [BerichtOpslagService] door een mock die [CircuitBreakerOpenException]
 * gooit; valideert de response-shape tegen de OpenAPI-spec.
 */
@QuarkusTest
class CircuitBreakerOpen503ContractTest {

    private val validationFilter = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtenmagazijn-api.yaml")
            .build(),
    )

    @BeforeEach
    fun installFailingService() {
        val failingService = object : BerichtOpslagService(
            repository = mockk(relaxed = true),
        ) {
            override fun opslaanBericht(
                afzender: String,
                ontvangerType: IdentificatienummerType,
                ontvangerWaarde: String,
                onderwerp: String,
                inhoud: String,
            ): Nothing = throw CircuitBreakerOpenException("circuit open (test)")
        }
        QuarkusMock.installMockForType(failingService, BerichtOpslagService::class.java)
    }

    @Test
    fun `503 Problem response respecteert OpenAPI spec`() {
        given()
            .filter(validationFilter)
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Test",
                  "inhoud": "Test"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(503)
            .contentType("application/problem+json")
            .body("status", `is`(503))
            .body("title", `is`("Service Unavailable"))
    }
}
