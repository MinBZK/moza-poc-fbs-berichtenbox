package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.ws.rs.InternalServerErrorException
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.matchesRegex
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Contracttest: 500 Problem-response masker interne details en bevat correlation-id.
 * Zonder masking lekken SQL/stacktrace-strings; die garantie is contract-kritiek.
 */
@QuarkusTest
class InternalError500ContractTest {

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
            ): Nothing = throw InternalServerErrorException("SELECT * FROM berichten WHERE secret=redacted")
        }
        QuarkusMock.installMockForType(failingService, BerichtOpslagService::class.java)
    }

    @Test
    fun `500 Problem response respecteert OpenAPI spec en maskeert detail`() {
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
            .statusCode(500)
            .contentType("application/problem+json")
            .body("status", `is`(500))
            .body("title", `is`("Internal Server Error"))
            .body("detail", not(org.hamcrest.Matchers.containsString("SELECT")))
            .body("instance", matchesRegex("^urn:uuid:[0-9a-f-]{36}$"))
    }
}
