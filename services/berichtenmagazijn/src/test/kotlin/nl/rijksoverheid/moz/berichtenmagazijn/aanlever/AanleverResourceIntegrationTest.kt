package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.matchesRegex
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
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
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Voorlopige aanslag 2026",
                  "inhoud": "Hierbij ontvangt u..."
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
            .header("API-Version", `is`("v1"))
            .header("X-Frame-Options", `is`("DENY"))
            .header("X-Content-Type-Options", `is`("nosniff"))
            .header("Strict-Transport-Security", containsString("max-age=31536000"))
            .header("Strict-Transport-Security", containsString("includeSubDomains"))
            .header("Content-Security-Policy", `is`("frame-ancestors 'none'"))
            .header("Referrer-Policy", `is`("no-referrer"))
            .header("Cache-Control", `is`("no-store"))
            .contentType("application/json")
            .body("berichtId", matchesRegex("[0-9a-f-]{36}"))
            .body("afzender", `is`("00000001003214345000"))
            .body("ontvanger.type", `is`("BSN"))
            .body("ontvanger.waarde", `is`("999993653"))
            .body("onderwerp", `is`("Voorlopige aanslag 2026"))
            .body("tijdstipOntvangst", notNullValue())
            .body("_links.self.href", containsString("/api/v1/berichten/"))
    }

    @Test
    fun `POST berichten met ontbrekende ontvanger retourneert 400 Problem JSON met API-Version header`() {
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
            // API-Version en security-headers horen óók op fout-responses te staan;
            // anders is het contract voor clients die op die headers steunen inconsistent.
            .header("API-Version", `is`("v1"))
            .header("X-Content-Type-Options", `is`("nosniff"))
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
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
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
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
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
        // Onderwerp met alleen spaties passeert OpenAPI-pattern (minLength=1) maar wordt
        // afgewezen door requireValid in Bericht.init → DomainValidationException.
        // De assertions hieronder borgen dat de DomainValidationExceptionMapper wint van
        // de generieke IllegalArgumentExceptionMapper (die zou maskeren naar 500 + correlation-id).
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
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
            .body("detail", `is`("onderwerp mag niet leeg zijn"))
            .body("instance", nullValue())
    }

    @Test
    fun `POST berichten met afzender van 8 cijfers wordt afgewezen door OIN-validatie als 400`() {
        // Border case voor de mapper-prioriteit: een afzender van 8 cijfers passeert
        // het OpenAPI-pattern niet meer (we hebben dit aangescherpt naar exact 20). Maar
        // mocht het ooit verzwakt worden, dan moet Oin.init alsnog een DomainValidationException
        // gooien die door de juiste mapper als 400 wordt afgehandeld — niet 500.
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "12345678",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Test",
                  "inhoud": "Inhoud"
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
    fun `POST berichten persisteert het bericht in de database`() {
        val responseBerichtId: String = given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Test persistentie",
                  "inhoud": "Inhoud"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
            .extract().path("berichtId")

        assert(responseBerichtId.isNotBlank())

        // Zoek het bericht op met de teruggegeven berichtId en verifieer dat alle
        // velden uit de request daadwerkelijk in de DB zijn beland (en correct
        // getypeerd zijn gehydrateerd via toDomain()).
        val opgeslagen = repository.findByBerichtId(java.util.UUID.fromString(responseBerichtId))
        assertNotNull(opgeslagen, "bericht ontbreekt in DB voor berichtId=$responseBerichtId")
        assertEquals("00000001003214345000", opgeslagen!!.afzender.waarde)
        assertEquals("999993653", opgeslagen.ontvanger.waarde)
        assertEquals(nl.rijksoverheid.moz.berichtenmagazijn.opslag.Bsn::class, opgeslagen.ontvanger::class)
        assertEquals("Test persistentie", opgeslagen.onderwerp)
        assertEquals("Inhoud", opgeslagen.inhoud)
    }
}
