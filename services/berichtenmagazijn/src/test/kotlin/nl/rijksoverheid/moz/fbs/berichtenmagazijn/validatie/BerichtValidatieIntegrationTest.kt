package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.NotFoundException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * End-to-end keten: HTTP POST → AanleverResource → BerichtOpslagService →
 * BerichtValidatieService → (gemockte) ProfielServiceClient → ExceptionMappers.
 */
@QuarkusTest
class BerichtValidatieIntegrationTest {

    @Inject
    lateinit var repository: BerichtRepository

    @Inject
    lateinit var bijlageRepository: BijlageRepository

    @Inject
    @RestClient
    lateinit var profielServiceClient: ProfielServiceClient

    private val testAfzender = "00000001003214345000"

    @BeforeEach
    @Transactional
    fun cleanDatabase() {
        bijlageRepository.deleteAll()
        repository.deleteAll()
    }

    @BeforeEach
    fun resetMockNaarToegestaan() {
        (profielServiceClient as MockProfielServiceClient).antwoordSupplier = { _, _ ->
            MockProfielServiceClient.defaultPartij(afzenderOin = testAfzender)
        }
    }

    @Test
    fun `aanlever met PDF bijlage en actieve voorkeur retourneert 201`() {
        val payload = Base64.getEncoder().encodeToString("pdf-bytes".toByteArray())

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$testAfzender",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Voorlopige aanslag",
                  "inhoud": "Inhoud",
                  "bijlagen": [
                    {"naam": "doc.pdf", "mimeType": "application/pdf", "inhoud": "$payload"}
                  ]
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
    }

    @Test
    fun `aanlever met PNG bijlage retourneert 400 Problem JSON met DomainValidationException-detail`() {
        val payload = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$testAfzender",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Voorlopige aanslag",
                  "inhoud": "Inhoud",
                  "bijlagen": [
                    {"naam": "plaatje.png", "mimeType": "image/png", "inhoud": "$payload"}
                  ]
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
            .body("title", `is`("Bad Request"))
            .body("detail", containsString("application/pdf"))
            .body("detail", containsString("image/png"))
    }

    @Test
    fun `aanlever zonder actieve berichtenbox-voorkeur retourneert 403 Problem JSON`() {
        (profielServiceClient as MockProfielServiceClient).antwoordSupplier = { _, _ ->
            // Partij heeft wel een profiel, maar geen OntvangViaBerichtenbox-voorkeur.
            PartijResponse(voorkeuren = emptyList())
        }
        val payload = Base64.getEncoder().encodeToString("pdf".toByteArray())

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$testAfzender",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Voorlopige aanslag",
                  "inhoud": "Inhoud",
                  "bijlagen": [
                    {"naam": "doc.pdf", "mimeType": "application/pdf", "inhoud": "$payload"}
                  ]
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(403)
            .contentType("application/problem+json")
            .body("status", `is`(403))
            .body("title", `is`("Forbidden"))
    }

    @Test
    fun `aanlever voor ondernemer (KVK) met voorkeur naar andere afzender retourneert 403 Problem JSON`() {
        (profielServiceClient as MockProfielServiceClient).antwoordSupplier = { _, _ ->
            MockProfielServiceClient.defaultPartij(afzenderOin = "00000001003214345999")
        }
        val payload = Base64.getEncoder().encodeToString("pdf".toByteArray())

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$testAfzender",
                  "ontvanger": {"type": "KVK", "waarde": "12345678"},
                  "onderwerp": "Factuur",
                  "inhoud": "Inhoud",
                  "bijlagen": [
                    {"naam": "doc.pdf", "mimeType": "application/pdf", "inhoud": "$payload"}
                  ]
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(403)
            .contentType("application/problem+json")
            .body("status", `is`(403))
    }

    @Test
    fun `aanlever voor onbekende ontvanger (404 profielservice) retourneert 403`() {
        (profielServiceClient as MockProfielServiceClient).antwoordSupplier = { _, _ ->
            throw NotFoundException()
        }
        val payload = Base64.getEncoder().encodeToString("pdf".toByteArray())

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$testAfzender",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Test",
                  "inhoud": "Inhoud",
                  "bijlagen": [
                    {"naam": "doc.pdf", "mimeType": "application/pdf", "inhoud": "$payload"}
                  ]
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(403)
            .contentType("application/problem+json")
            .body("status", `is`(403))
    }

    @Test
    fun `aanlever zonder bijlagen en met actieve voorkeur retourneert 201`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$testAfzender",
                  "ontvanger": {"type": "BSN", "waarde": "999993653"},
                  "onderwerp": "Voorlopige aanslag",
                  "inhoud": "Inhoud"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
    }
}
