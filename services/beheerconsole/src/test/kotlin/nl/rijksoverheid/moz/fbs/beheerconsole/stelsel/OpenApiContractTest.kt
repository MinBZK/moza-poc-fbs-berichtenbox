package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.ws.rs.WebApplicationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

/** Elk antwoord van `/stelsel` tegen de spec: het overzicht, ook bij een ongeldig document, en de fouten. */
@QuarkusTest
@TestProfile(StelselProfiel::class)
class OpenApiContractTest {

    private val specPad = "openapi/beheerconsole-api.yaml"

    private val filter = OpenApiValidationFilter(OpenApiInteractionValidator.createForSpecificationUrl(specPad).build())

    // Voor het verzoek dat bewust een niet-aangeboden mediatype vraagt: alleen het antwoord telt.
    private val alleenAntwoord = OpenApiValidationFilter(
        OpenApiInteractionValidator.createForSpecificationUrl(specPad)
            .withLevelResolver(
                LevelResolver.create().withLevel("validation.request.accept.notAllowed", ValidationReport.Level.IGNORE).build(),
            )
            .build(),
    )

    @AfterEach
    fun herstel() {
        StubStelselregister.antwoord = { error("geen antwoord ingesteld") }
    }

    @Test
    fun `een geldig overzicht volgt de spec`() {
        StubStelselregister.antwoord = { Testketen.geldig.document(Instant.now()) }

        given().filter(filter).`when`().get("/api/v1/stelsel").then().statusCode(200)
    }

    @Test
    fun `een ongeldig overzicht zonder inhoud volgt de spec`() {
        StubStelselregister.antwoord = { "geen-jws" }

        given().filter(filter).`when`().get("/api/v1/stelsel").then().statusCode(200)
    }

    @Test
    fun `een ketenfout volgt de spec`() {
        StubStelselregister.antwoord = { throw WebApplicationException(500) }

        given().filter(filter).`when`().get("/api/v1/stelsel").then().statusCode(502)
    }

    // Een ongeldig document wordt getoond zoals het is, ook met een OIN of adres van een andere vorm.
    @Test
    fun `een ongeldig document met afwijkende inhoud volgt de spec`() {
        StubStelselregister.antwoord = { Testketen.ander.document(Instant.now(), organisaties = listOf(Triple("123", "X", "http://a b"))) }

        given().filter(filter).`when`().get("/api/v1/stelsel").then().statusCode(200)
    }

    // Een uitgever die milliseconden stuurt: het document is ongeldig en heeft geen inhoud, maar het antwoord blijft binnen de spec.
    @Test
    fun `tijdstippen buiten bereik volgen de spec`() {
        StubStelselregister.antwoord = { Testketen.ander.document(Instant.now().plusSeconds(300_000_000_000)) }

        given().filter(filter).`when`().get("/api/v1/stelsel").then().statusCode(200)
    }

    @Test
    fun `een onverwachte fout volgt de spec`() {
        StubStelselregister.antwoord = { throw IllegalStateException("onverwacht") }

        given().filter(filter).`when`().get("/api/v1/stelsel").then().statusCode(500).contentType("application/problem+json")
    }

    @Test
    fun `een verkeerd mediatype volgt de spec`() {
        given().filter(alleenAntwoord).accept("application/xml").`when`().get("/api/v1/stelsel").then().statusCode(406)
    }
}
