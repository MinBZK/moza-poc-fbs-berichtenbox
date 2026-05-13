package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

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
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
class OphaalResourceIntegrationTest {

    @Inject lateinit var berichtRepository: BerichtRepository
    @Inject lateinit var bijlageRepository: BijlageRepository
    @Inject lateinit var statusRepository: BerichtStatusRepository

    private val ontvanger: Identificatienummer = Bsn("999993653")
    private val ontvangerHeader = "BSN:999993653"
    private val andereOntvangerHeader = "BSN:123456782"

    @BeforeEach
    @Transactional
    fun cleanDatabase() {
        statusRepository.deleteAll()
        bijlageRepository.deleteAll()
        berichtRepository.deleteAll()
    }

    @Transactional
    fun insertBericht(berichtId: UUID = UUID.randomUUID(), onderwerp: String = "Test"): Bericht {
        val b = Bericht(
            berichtId = berichtId,
            afzender = Oin("00000001003214345000"),
            ontvanger = ontvanger,
            onderwerp = onderwerp,
            inhoud = "Inhoud van $onderwerp",
            tijdstipOntvangst = Instant.now(),
        )
        berichtRepository.save(b)
        return b
    }

    @Transactional
    fun insertBijlage(berichtId: UUID, content: ByteArray): UUID {
        val bijlageId = UUID.randomUUID()
        bijlageRepository.save(
            Bijlage(
                bijlageId = bijlageId,
                berichtId = berichtId,
                naam = "test.pdf",
                mimeType = "application/pdf",
                content = content,
            ),
        )
        return bijlageId
    }

    @Test
    fun `GET berichten levert lijst met paginatie en _links`() {
        insertBericht(onderwerp = "Eerste")
        insertBericht(onderwerp = "Tweede")

        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten?page=0&pageSize=10")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .header("API-Version", `is`("v1"))
            .body("totalElements", `is`(2))
            .body("berichten", hasSize<Any>(2))
            .body("berichten[0].onderwerp", notNullValue())
            .body("berichten[0]._links.self.href", containsString("/api/v1/berichten/"))
            .body("_links.self.href", containsString("/api/v1/berichten"))
    }

    @Test
    fun `GET berichten filtert op afzender`() {
        insertBericht()
        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten?afzender=99999999999999999999")
            .then()
            .statusCode(200)
            .body("totalElements", `is`(0))
    }

    @Test
    fun `GET berichten zonder X-Ontvanger header retourneert 400`() {
        given()
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET bericht by id levert bericht met inhoud, bijlagen-metadata en _links`() {
        val b = insertBericht(onderwerp = "Inhoud-test")
        val bijlageId = insertBijlage(b.berichtId, "%PDF-1.4".toByteArray())

        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten/${b.berichtId}")
            .then()
            .statusCode(200)
            .body("berichtId", `is`(b.berichtId.toString()))
            .body("onderwerp", `is`("Inhoud-test"))
            .body("inhoud", containsString("Inhoud van"))
            .body("bijlagen", hasSize<Any>(1))
            .body("bijlagen[0].bijlageId", `is`(bijlageId.toString()))
            .body("bijlagen[0].naam", `is`("test.pdf"))
            .body("bijlagen[0].mimeType", `is`("application/pdf"))
            .body("bijlagen[0]._links.self.href", containsString("/bijlagen/$bijlageId"))
    }

    @Test
    fun `GET bericht by id van andere ontvanger geeft 403`() {
        val b = insertBericht()

        given()
            .header("X-Ontvanger", andereOntvangerHeader)
            .`when`().get("/api/v1/berichten/${b.berichtId}")
            .then()
            .statusCode(403)
            .contentType("application/problem+json")
            .body("status", `is`(403))
    }

    @Test
    fun `GET bericht by id voor onbekend bericht geeft 404`() {
        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten/${UUID.randomUUID()}")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET bijlage levert bytes met het bij aanlevering geregistreerde MIME-type`() {
        val b = insertBericht()
        val payload = "Hello PDF".toByteArray()
        val bijlageId = insertBijlage(b.berichtId, payload)

        val bytes = given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten/${b.berichtId}/bijlagen/$bijlageId")
            .then()
            .statusCode(200)
            .contentType("application/pdf")
            .extract().asByteArray()

        assert(bytes.contentEquals(payload)) { "Bytes match niet: kreeg ${bytes.size} bytes" }
    }

    @Test
    fun `GET bijlage met andere ontvanger geeft 403`() {
        val b = insertBericht()
        val bijlageId = insertBijlage(b.berichtId, byteArrayOf(1, 2, 3))

        given()
            .header("X-Ontvanger", andereOntvangerHeader)
            .`when`().get("/api/v1/berichten/${b.berichtId}/bijlagen/$bijlageId")
            .then()
            .statusCode(403)
    }

    @Test
    fun `GET bijlage met onbekend bijlageId geeft 404`() {
        val b = insertBericht()

        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten/${b.berichtId}/bijlagen/${UUID.randomUUID()}")
            .then()
            .statusCode(404)
    }

    @Test
    fun `GET met ongeldige X-Ontvanger header retourneert 400`() {
        given()
            .header("X-Ontvanger", "INVALID:abc")
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .body("status", equalTo(400))
    }
}
