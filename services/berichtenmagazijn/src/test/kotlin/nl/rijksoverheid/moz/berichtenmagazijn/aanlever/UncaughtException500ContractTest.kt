package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.IdentificatienummerType
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.matchesRegex
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Contracttest: een willekeurige onverwachte exception (bv. een falende observability-
 * export naar ClickHouse, of andere niet-WebApplicationException uit een interceptor of
 * library) mag nooit als raw stacktrace bij de client komen. [UncaughtExceptionMapper]
 * vangt dit en levert een gemaskeerd Problem 500.
 */
@QuarkusTest
class UncaughtException500ContractTest {

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
            ): Nothing = throw IOException("ClickHouse onbereikbaar: connection refused at /1.2.3.4:8123 stacktrace at nl.example.Foo.bar(Foo.kt:42)")
        }
        QuarkusMock.installMockForType(failingService, BerichtOpslagService::class.java)
    }

    @Test
    fun `onverwachte exception levert gemaskeerd Problem 500 zonder stacktrace`() {
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
            .statusCode(500)
            .contentType("application/problem+json")
            .body("status", `is`(500))
            .body("title", `is`("Internal Server Error"))
            .body("detail", not(containsString("ClickHouse")))
            .body("detail", not(containsString("stacktrace")))
            .body("detail", not(containsString("connection refused")))
            .body("instance", matchesRegex("^urn:uuid:[0-9a-f-]{36}$"))
    }
}
