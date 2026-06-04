package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class OpenApiContractTest {

    @Inject
    lateinit var repository: BerichtRepository

    // Valideert zowel request als response tegen de spec. De HAL `_links.*.href` zijn
    // bewust relatieve URI-references; networknt 2.x (via openapi-request-validator) dwingt
    // `format: uri` sinds deze versie strikt als absolute RFC 3986 URI af, dus die assertie
    // op WARN zodat de contractcheck het vorige gedrag behoudt. TODO(#76): spec aanlijnen
    // op `uri-reference` en deze downgrade verwijderen.
    private val validationFilter = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtenmagazijn-api.yaml")
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.response.body.schema.format.uri", ValidationReport.Level.WARN)
                    .build()
            )
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
                    .withLevel("validation.request.body.schema.maxLength", ValidationReport.Level.IGNORE)
                    .withLevel("validation.request.body.schema.pattern", ValidationReport.Level.IGNORE)
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
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
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

    @Test
    fun `request met optionele publicatietijdstip respecteert OpenAPI spec`() {
        // Spec breidt BerichtAanleverenRequest uit met optioneel publicatietijdstip;
        // borg dat zowel request (RFC 3339) als response (BerichtResponse.publicatietijdstip
        // is required) tegen de spec valideren met de validatie-filter actief.
        given()
            .filter(validationFilter)
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Met publicatietijdstip",
                  "inhoud": "Inhoud",
                  "publicatietijdstip": "2026-12-31T08:00:00Z"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
    }
}
