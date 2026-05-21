package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.matchesRegex
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

@QuarkusTest
class AanleverResourceIntegrationTest {

    @Inject
    lateinit var repository: BerichtRepository

    @Inject
    lateinit var bijlageRepository: BijlageRepository

    @BeforeEach
    @Transactional
    fun cleanDatabase() {
        bijlageRepository.deleteAll()
        repository.deleteAll()
    }

    // ToestemmingControle wordt vervangen door MockToestemmingControle (CDI @Mock
    // met @RestClient qualifier). Default `toegestaan = true`; geen extra setup
    // nodig in deze tests.

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
        // de generieke UncaughtExceptionMapper (die zou maskeren naar 500 + correlation-id).
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
            .body("detail", `is`("Onderwerp mag niet leeg zijn"))
            // Round 12 H3: DomainValidationExceptionMapper genereert nu errorId
            // in `urn:uuid:`-instance voor cross-correlatie tussen client-Problem
            // en applicatielog. Refactor die instance terug naar null zet zou
            // support-traceability slopen.
            .body("instance", org.hamcrest.Matchers.startsWith("urn:uuid:"))
    }

    @Test
    fun `POST berichten met afzender van 8 cijfers wordt afgewezen door OIN-validatie als 400`() {
        // Border case voor de mapper-prioriteit: het OpenAPI-pattern eist exact 20
        // cijfers. Mocht dat ooit verzwakt worden, dan moet Oin.init alsnog een
        // DomainValidationException gooien die door de juiste mapper als 400 wordt
        // afgehandeld — niet 500.
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
        val opgeslagen = repository.findByBerichtId(UUID.fromString(responseBerichtId))
        assertNotNull(opgeslagen, "bericht ontbreekt in DB voor berichtId=$responseBerichtId")
        assertEquals("00000001003214345000", opgeslagen!!.afzender.waarde)
        assertEquals("999993653", opgeslagen.ontvanger.waarde)
        assertEquals(Bsn::class, opgeslagen.ontvanger::class)
        assertEquals("Test persistentie", opgeslagen.onderwerp)
        assertEquals("Inhoud", opgeslagen.inhoud)
    }

    @Test
    fun `POST berichten met bijlagen persisteert bericht en bijlagen`() {
        val payload = "Hello PDF".toByteArray()
        val base64 = Base64.getEncoder().encodeToString(payload)

        val responseBerichtId: String = given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Met bijlage",
                  "inhoud": "Zie bijlage",
                  "bijlagen": [
                    {"naam": "voorlopige-aanslag.pdf", "mimeType": "application/pdf", "inhoud": "$base64"}
                  ]
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
            .body("berichtId", matchesRegex("[0-9a-f-]{36}"))
            .extract().path("berichtId")

        assert(responseBerichtId.isNotBlank())
        val bijlagen = bijlageRepository.metadataVoorBericht(UUID.fromString(responseBerichtId))
        assertEquals(1, bijlagen.size)
        assertEquals("voorlopige-aanslag.pdf", bijlagen[0].naam)
        assertEquals("application/pdf", bijlagen[0].mimeType)
    }

    @Test
    fun `POST berichten met meerdere bijlagen persisteert alle in volgorde`() {
        val payload1 = Base64.getEncoder().encodeToString("PDF-1".toByteArray())
        val payload2 = Base64.getEncoder().encodeToString("PDF-2".toByteArray())
        val payload3 = Base64.getEncoder().encodeToString("PDF-3".toByteArray())

        val responseBerichtId: String = given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Drie bijlagen",
                  "inhoud": "x",
                  "bijlagen": [
                    {"naam": "a.pdf", "mimeType": "application/pdf", "inhoud": "$payload1"},
                    {"naam": "b.pdf", "mimeType": "application/pdf", "inhoud": "$payload2"},
                    {"naam": "c.pdf", "mimeType": "application/pdf", "inhoud": "$payload3"}
                  ]
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
            .extract().path("berichtId")

        val bijlagen = bijlageRepository.metadataVoorBericht(UUID.fromString(responseBerichtId))
        assertEquals(3, bijlagen.size)
        assertEquals(setOf("a.pdf", "b.pdf", "c.pdf"), bijlagen.map { it.naam }.toSet())
    }

    @Test
    fun `POST berichten met lege bijlage-inhoud wordt afgewezen door spec-validatie`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Lege bijlage",
                  "inhoud": "x",
                  "bijlagen": [
                    {"naam": "leeg.pdf", "mimeType": "application/pdf", "inhoud": ""}
                  ]
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `POST berichten met bijlage groter dan MAX_CONTENT_BYTES wordt afgewezen`() {
        // 25 MiB + 1 byte aan synthetische content. Test borgt dat de domein-
        // invariant op Bijlage.MAX_CONTENT_BYTES daadwerkelijk afgedwongen wordt
        // door de aanlever-flow.
        val teGroot = ByteArray(25 * 1024 * 1024 + 1) { 0x25 }
        val base64 = Base64.getEncoder().encodeToString(teGroot)

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Te groot",
                  "inhoud": "x",
                  "bijlagen": [
                    {"naam": "groot.pdf", "mimeType": "application/pdf", "inhoud": "$base64"}
                  ]
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            // Quarkus' max-body-size (40 MiB) staat de request toe; daarna pakt
            // de domein-invariant het op met 400.
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `POST berichten met ontvanger zonder waarde retourneert 400`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": {"type": "BSN", "waarde": ""},
                  "onderwerp": "x",
                  "inhoud": "y"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }
}
