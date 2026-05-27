package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Valideert dat de nieuwe Ophaal- en Beheer-endpoints zowel request als response
 * compleet matchen met de OpenAPI-spec. Borgt dat schema-aanpassingen en
 * resource-implementatie synchroon blijven.
 */
@QuarkusTest
class OphaalBeheerOpenApiContractTest {

    @Inject lateinit var berichtRepository: BerichtRepository
    @Inject lateinit var bijlageRepository: BijlageRepository
    @Inject lateinit var statusRepository: BerichtStatusRepository

    private val validationFilter = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtenmagazijn-api.yaml")
            .build(),
    )

    @BeforeEach
    @Transactional
    fun cleanDatabase() {
        statusRepository.deleteAll()
        bijlageRepository.deleteAll()
        berichtRepository.deleteAll()
    }

    @Transactional
    fun insertBericht(berichtId: UUID = UUID.randomUUID()): UUID {
        berichtRepository.save(
            Bericht(
                berichtId = berichtId,
                afzender = Oin("00000001003214345000"),
                ontvanger = Bsn("999993653"),
                onderwerp = "Contract test",
                inhoud = "Contract inhoud",
                tijdstipOntvangst = Instant.now(),
                publicatietijdstip = Instant.now(),
            ),
        )
        return berichtId
    }

    @Transactional
    fun insertBijlage(berichtId: UUID): UUID {
        val bijlageId = UUID.randomUUID()
        bijlageRepository.save(
            Bijlage(
                bijlageId = bijlageId,
                berichtId = berichtId,
                naam = "contract.pdf",
                mimeType = "application/pdf",
                content = byteArrayOf(0x25, 0x50, 0x44, 0x46),
            ),
        )
        return bijlageId
    }

    @Test
    fun `GET berichten respecteert spec`() {
        insertBericht()
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten?page=0&pageSize=20")
            .then()
            .statusCode(200)
    }

    @Test
    fun `GET bericht by id respecteert spec`() {
        val id = insertBericht()
        insertBijlage(id)
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten/$id")
            .then()
            .statusCode(200)
    }

    @Test
    fun `GET bijlage respecteert spec`() {
        val berichtId = insertBericht()
        val bijlageId = insertBijlage(berichtId)
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(200)
    }

    @Test
    fun `PATCH status respecteert spec`() {
        val id = insertBericht()
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", "BSN:999993653")
            .contentType("application/merge-patch+json")
            .body("""{"gelezen": true}""")
            .`when`().patch("/api/v1/berichten/$id")
            .then()
            .statusCode(200)
    }

    @Test
    fun `DELETE respecteert spec`() {
        val id = insertBericht()
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().delete("/api/v1/berichten/$id")
            .then()
            .statusCode(204)
    }

    @Test
    fun `404 Problem response op GET bericht respecteert spec`() {
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten/${UUID.randomUUID()}")
            .then()
            .statusCode(404)
    }

    @Test
    fun `403 Problem response op GET bericht respecteert spec`() {
        val id = insertBericht()
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", "BSN:123456782")
            .`when`().get("/api/v1/berichten/$id")
            .then()
            .statusCode(403)
    }

    // Voor expliciet invalide requests gebruiken we de validationFilter niet:
    // hij weigert het request vóór verzending wanneer het zelf het schema schendt.
    // De server-side response wordt nog wél tegen het Problem-schema gecheckt via
    // de content-type/asserts.
    @Test
    fun `400 Problem response op ongeldige X-Ontvanger header`() {
        given()
            .header("X-Ontvanger", "BSN:nietnumeriek")
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", org.hamcrest.Matchers.equalTo(400))
    }

    @Test
    fun `400 Problem response op pageSize te hoog`() {
        given()
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten?page=0&pageSize=999")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", org.hamcrest.Matchers.equalTo(400))
    }

    @Test
    fun `400 Problem response op lege PATCH body respecteert spec`() {
        val id = insertBericht()
        given()
            .filter(validationFilter)
            .header("X-Ontvanger", "BSN:999993653")
            .contentType("application/merge-patch+json")
            .body("{}")
            .`when`().patch("/api/v1/berichten/$id")
            .then()
            .statusCode(400)
    }
}
