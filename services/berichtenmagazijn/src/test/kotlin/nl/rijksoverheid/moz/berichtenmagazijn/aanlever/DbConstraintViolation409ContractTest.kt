package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.`is`
import org.hibernate.exception.ConstraintViolationException as HibernateConstraintViolationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * Contracttest: 409 Problem-response wanneer de DB een unique-key-violation meldt
 * (SQL state 23505). Valideert response-shape tegen de OpenAPI-spec.
 */
@QuarkusTest
class DbConstraintViolation409ContractTest {

    private val validationFilter = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtenmagazijn-api.yaml")
            .build(),
    )

    @BeforeEach
    fun installFailingService() {
        val failingService = object : BerichtOpslagService(
            repository = io.mockk.mockk(relaxed = true),
        ) {
            override fun opslaanBericht(
                afzender: String,
                ontvanger: String,
                onderwerp: String,
                inhoud: String,
            ): Nothing = throw HibernateConstraintViolationException(
                "unique violation",
                SQLException("duplicate key", "23505"),
                "uq_bericht_idempotency",
            )
        }
        QuarkusMock.installMockForType(failingService, BerichtOpslagService::class.java)
    }

    @Test
    fun `409 Problem response respecteert OpenAPI spec`() {
        given()
            .filter(validationFilter)
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": "999993653",
                  "onderwerp": "Test",
                  "inhoud": "Test"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("status", `is`(409))
            .body("title", `is`("Conflict"))
    }
}
