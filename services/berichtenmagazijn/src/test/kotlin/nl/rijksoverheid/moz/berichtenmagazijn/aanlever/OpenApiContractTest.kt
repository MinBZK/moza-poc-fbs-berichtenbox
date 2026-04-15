package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class OpenApiContractTest {

    @Inject
    lateinit var repository: BerichtRepository

    // Valideert zowel request als response tegen de spec.
    private val validationFilter = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtenmagazijn-api.yaml")
            .build()
    )

    // Valideert alleen de response, niet de request. Voor tests die bewust een
    // ongeldige request sturen (ontbrekende verplichte velden).
    private val responseOnlyFilter = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtenmagazijn-api.yaml")
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.request.body.schema.required", ValidationReport.Level.IGNORE)
                    .withLevel("validation.request.body.schema.type", ValidationReport.Level.IGNORE)
                    .withLevel("validation.request.body.schema.minLength", ValidationReport.Level.IGNORE)
                    .withLevel("validation.request.body.missing", ValidationReport.Level.IGNORE)
                    .build()
            )
            .build()
    )

    @BeforeEach
    @Transactional
    fun cleanDatabase() {
        repository.deleteAll()
    }

    @Test
    fun `happy path respecteert OpenAPI spec (request en response)`() {
        given()
            .filter(validationFilter)
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": "999993653",
                  "onderwerp": "Contract test",
                  "inhoud": "Contract test inhoud"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
    }

    @Test
    fun `400 Problem response respecteert OpenAPI spec`() {
        given()
            .filter(responseOnlyFilter)
            .contentType(ContentType.JSON)
            .body("""{"afzender": "a"}""")
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
    }
}
