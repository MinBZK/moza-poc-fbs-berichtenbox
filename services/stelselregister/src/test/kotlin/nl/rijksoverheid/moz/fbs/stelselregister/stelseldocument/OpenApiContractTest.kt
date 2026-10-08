package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.MessageResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import com.atlassian.oai.validator.schema.SchemaValidator
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.OpenAPIV3Parser
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * De antwoorden tegen de spec. Voor het document zelf kan de validator alleen status, headers en
 * mediatype toetsen: OpenAPI 3.0 hangt geen schema aan een `application/jose`-body. Header en
 * payload worden daarom hier gedecodeerd en elk tegen hun eigen schema uit de spec gelegd.
 */
@QuarkusTest
class OpenApiContractTest {

    private val specPad = "openapi/stelselregister-api.yaml"
    private val filter = OpenApiValidationFilter(OpenApiInteractionValidator.createForSpecificationUrl(specPad).build())

    // Voor het verzoek dat bewust een niet-aangeboden mediatype vraagt: alleen het antwoord telt.
    private val alleenAntwoord = OpenApiValidationFilter(
        OpenApiInteractionValidator.createForSpecificationUrl(specPad)
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.request.accept.notAllowed", ValidationReport.Level.IGNORE)
                    .build(),
            )
            .build(),
    )
    private val spec: OpenAPI = OpenAPIV3Parser().read(specPad)
    private val schemas = SchemaValidator(spec, MessageResolver())

    private val pad = "/api/v1/stelseldocument"

    private fun voldoetAan(schemanaam: String, json: JsonNode) = schemas.validate(
        { json },
        spec.components.schemas.getValue(schemanaam),
        "stelseldocument",
    )

    @Test
    fun `het document respecteert de spec`() {
        given().filter(filter).`when`().get(pad).then().statusCode(200)
    }

    @Test
    fun `een 304 respecteert de spec`() {
        val etag = given().get(pad).then().extract().header("ETag")

        given().filter(filter).header("If-None-Match", etag).`when`().get(pad).then().statusCode(304)
    }

    @Test
    fun `een 406 respecteert het foutcontract`() {
        given().filter(alleenAntwoord).accept("application/json").`when`().get(pad).then().statusCode(406)
    }

    @Test
    fun `een 503 respecteert het foutcontract`() {
        QuarkusMock.installMockForType(
            Clock.fixed(Instant.now().plus(Duration.ofDays(2)), ZoneOffset.UTC),
            Clock::class.java,
        )

        given().filter(filter).`when`().get(pad).then().statusCode(503)
    }

    @Test
    fun `de gedecodeerde header voldoet aan zijn schema`() {
        val header = Afnemer.header(given().get(pad).then().extract().asString())
        val rapport = voldoetAan("StelseldocumentHeader", header)

        assertFalse(rapport.hasErrors()) { rapport.messages.toString() }
    }

    @Test
    fun `de gedecodeerde payload voldoet aan zijn schema`() {
        val payload = Afnemer.payload(given().get(pad).then().extract().asString())
        val rapport = voldoetAan("StelseldocumentPayload", payload)

        assertFalse(rapport.hasErrors()) { rapport.messages.toString() }
    }

    // Zonder deze tegenproef zou een schemavalidatie die niets controleert ook groen zijn.
    @Test
    fun `een header met een extra veld of een ander algoritme voldoet niet`() {
        val header = Afnemer.header(given().get(pad).then().extract().asString())
        val metJku = header.deepCopy<ObjectNode>().put("jku", "https://x.example")
        val metNone = header.deepCopy<ObjectNode>().put("alg", "none")

        assertTrue(voldoetAan("StelseldocumentHeader", metJku).hasErrors())
        assertTrue(voldoetAan("StelseldocumentHeader", metNone).hasErrors())
    }
}
