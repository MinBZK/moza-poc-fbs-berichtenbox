package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

/** Het testprofiel legt geen root vast: zo staat de console in dev, en dan zegt hij dat ook. */
@QuarkusTest
class StelselZonderRootTest {

    private val filter = OpenApiValidationFilter(OpenApiInteractionValidator.createForSpecificationUrl("openapi/beheerconsole-api.yaml").build())

    @AfterEach
    fun herstel() {
        StubStelselregister.antwoord = { error("geen antwoord ingesteld") }
    }

    @Test
    fun `zonder root is het overzicht niet getoetst, maar wel compleet`() {
        StubStelselregister.antwoord = { Testketen.geldig.document(Instant.now()) }

        given().filter(filter).`when`().get("/api/v1/stelsel").then()
            .statusCode(200)
            .body("verificatie.status", equalTo("NIET_GETOETST"))
            .body("verificatie.reden", notNullValue())
            .body("document.uitgever", equalTo(Testketen.OIN))
    }
}
