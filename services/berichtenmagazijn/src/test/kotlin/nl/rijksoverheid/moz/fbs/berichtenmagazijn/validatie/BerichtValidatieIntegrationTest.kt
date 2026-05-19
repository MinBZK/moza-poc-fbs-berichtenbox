package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
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
 * BerichtValidatieService → (gemockte) ToestemmingControle → ExceptionMappers.
 *
 * Borgt dat een ongeldig MIME-type een 400 oplevert en geweigerde toestemming
 * een 403 — beide in Problem JSON, met correct status-veld.
 */
@QuarkusTest
class BerichtValidatieIntegrationTest {

    @Inject
    lateinit var repository: BerichtRepository

    @Inject
    lateinit var bijlageRepository: BijlageRepository

    @Inject
    @RestClient
    lateinit var toestemmingControle: ToestemmingControle

    @BeforeEach
    @Transactional
    fun cleanDatabase() {
        bijlageRepository.deleteAll()
        repository.deleteAll()
    }

    @BeforeEach
    fun resetMockNaarToegestaan() {
        // Default: alles toegestaan. Per-test override gebeurt in de test zelf.
        (toestemmingControle as MockToestemmingControle).resultaat =
            ToestemmingAntwoord(toegestaan = true)
    }

    @Test
    fun `aanlever met PDF bijlage en toestemming retourneert 201`() {
        val payload = Base64.getEncoder().encodeToString("pdf-bytes".toByteArray())

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
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
                  "afzender": "00000001003214345000",
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
    fun `aanlever zonder toestemming retourneert 403 Problem JSON`() {
        (toestemmingControle as MockToestemmingControle).resultaat =
            ToestemmingAntwoord(toegestaan = false)
        val payload = Base64.getEncoder().encodeToString("pdf".toByteArray())

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
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
    fun `aanlever voor ondernemer (KVK) zonder toestemming retourneert 403 Problem JSON`() {
        (toestemmingControle as MockToestemmingControle).resultaat =
            ToestemmingAntwoord(toegestaan = false)
        val payload = Base64.getEncoder().encodeToString("pdf".toByteArray())

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
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
    fun `aanlever zonder bijlagen en met toestemming retourneert 201`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
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
