package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.matchesRegex
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class AanleverResourceIntegrationTest {

    @Inject
    lateinit var repository: BerichtRepository

    @BeforeEach
    @Transactional
    fun cleanDatabase() {
        repository.deleteAll()
    }

    @Test
    fun `POST berichten met geldige payload retourneert 201 met bericht en _links_self`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": "999993653",
                  "onderwerp": "Voorlopige aanslag 2026",
                  "inhoud": "Hierbij ontvangt u..."
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
            .header("API-Version", `is`("0.1.0"))
            .header("X-Frame-Options", `is`("DENY"))
            .header("X-Content-Type-Options", `is`("nosniff"))
            .header("Cache-Control", `is`("no-store"))
            .contentType("application/json")
            .body("berichtId", matchesRegex("[0-9a-f-]{36}"))
            .body("afzender", `is`("00000001003214345000"))
            .body("ontvanger", `is`("999993653"))
            .body("onderwerp", `is`("Voorlopige aanslag 2026"))
            .body("tijdstip", notNullValue())
            .body("_links.self.href", containsString("/api/v1/berichten/"))
    }

    @Test
    fun `POST berichten met ontbrekende ontvanger retourneert 400 Problem JSON`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "onderwerp": "Voorlopige aanslag 2026",
                  "inhoud": "Hierbij ontvangt u..."
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
            .body("title", `is`("Bad Request"))
    }

    @Test
    fun `POST berichten met lege onderwerp retourneert 400 Problem JSON`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": "999993653",
                  "onderwerp": "",
                  "inhoud": "Hierbij ontvangt u..."
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
    }

    @Test
    fun `POST berichten met te lange onderwerp retourneert 400 Problem JSON`() {
        val tooLong = "x".repeat(256)
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": "999993653",
                  "onderwerp": "$tooLong",
                  "inhoud": "Hierbij ontvangt u..."
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `POST berichten met whitespace-only onderwerp wordt afgevangen door domein-init en retourneert 400`() {
        // Onderwerp met alleen spaties passeert minLength=1 maar wordt afgewezen door
        // het init-block van Bericht. Zonder IllegalArgumentExceptionMapper zou dit 500 worden.
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": "999993653",
                  "onderwerp": "   ",
                  "inhoud": "Hierbij ontvangt u..."
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
            .body("title", `is`("Bad Request"))
            .body("detail", containsString("onderwerp"))
    }

    @Test
    fun `POST berichten persisteert het bericht in H2`() {
        val responseBerichtId: String = given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": "999993653",
                  "onderwerp": "Test persistentie",
                  "inhoud": "Inhoud"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
            .extract().path("berichtId")

        val opgeslagenAantal = repository.count()
        assert(opgeslagenAantal == 1L) { "Verwacht 1 bericht in DB, kreeg $opgeslagenAantal" }
        assert(responseBerichtId.isNotBlank())
    }
}
