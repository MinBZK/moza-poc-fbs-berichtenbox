package nl.rijksoverheid.moz.fbs.berichtenmagazijn.beheer

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
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusPatch
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import jakarta.ws.rs.NotFoundException
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
class BeheerResourceIntegrationTest {

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
    fun insertBericht(): Bericht {
        val b = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin("00000001003214345000"),
            ontvanger = ontvanger,
            onderwerp = "Beheer-test",
            inhoud = "Inhoud",
            tijdstipOntvangst = Instant.now(),
        )
        berichtRepository.save(b)
        return b
    }

    @Test
    fun `PATCH zet gelezen en map en retourneert bericht met status`() {
        val b = insertBericht()

        given()
            .header("X-Ontvanger", ontvangerHeader)
            .contentType("application/merge-patch+json")
            .body("""{"gelezen": true, "map": "archief"}""")
            .`when`().patch("/api/v1/berichten/${b.berichtId}")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .body("berichtId", `is`(b.berichtId.toString()))
            .body("status.gelezen", `is`(true))
            .body("status.map", `is`("archief"))
            .body("status.gewijzigdOp", `is`(org.hamcrest.Matchers.notNullValue()))
    }

    @Test
    fun `PATCH gelezen-only laat map ongewijzigd`() {
        val b = insertBericht()
        // Eerst map zetten
        given()
            .header("X-Ontvanger", ontvangerHeader)
            .contentType("application/merge-patch+json")
            .body("""{"map": "werk"}""")
            .`when`().patch("/api/v1/berichten/${b.berichtId}")
            .then().statusCode(200)

        // Daarna alleen gelezen wijzigen
        given()
            .header("X-Ontvanger", ontvangerHeader)
            .contentType("application/merge-patch+json")
            .body("""{"gelezen": true}""")
            .`when`().patch("/api/v1/berichten/${b.berichtId}")
            .then()
            .statusCode(200)
            .body("status.gelezen", `is`(true))
            .body("status.map", `is`("werk"))
    }

    @Test
    fun `PATCH met lege body geeft 400 (geen no-op accepteren)`() {
        val b = insertBericht()
        given()
            .header("X-Ontvanger", ontvangerHeader)
            .contentType("application/merge-patch+json")
            .body("{}")
            .`when`().patch("/api/v1/berichten/${b.berichtId}")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `PATCH op andermans bericht geeft 403`() {
        val b = insertBericht()
        given()
            .header("X-Ontvanger", andereOntvangerHeader)
            .contentType("application/merge-patch+json")
            .body("""{"gelezen": true}""")
            .`when`().patch("/api/v1/berichten/${b.berichtId}")
            .then().statusCode(403)
    }

    @Test
    fun `PATCH op onbekend bericht geeft 404`() {
        given()
            .header("X-Ontvanger", ontvangerHeader)
            .contentType("application/merge-patch+json")
            .body("""{"gelezen": true}""")
            .`when`().patch("/api/v1/berichten/${UUID.randomUUID()}")
            .then().statusCode(404)
    }

    @Test
    fun `DELETE soft-delete maakt bericht onzichtbaar voor GET`() {
        val b = insertBericht()

        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().delete("/api/v1/berichten/${b.berichtId}")
            .then()
            .statusCode(204)

        // Bericht is daarna niet meer opvraagbaar
        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten/${b.berichtId}")
            .then().statusCode(404)
    }

    @Test
    fun `DELETE is idempotent conform RFC 9110 - tweede DELETE door dezelfde ontvanger geeft 204`() {
        val b = insertBericht()
        given().header("X-Ontvanger", ontvangerHeader)
            .`when`().delete("/api/v1/berichten/${b.berichtId}")
            .then().statusCode(204)

        // Tweede DELETE door dezelfde rechtmatige ontvanger MOET 204 geven —
        // anders kan een client die na netwerkfout opnieuw probeert niet
        // onderscheiden of het bericht ooit bestond.
        given().header("X-Ontvanger", ontvangerHeader)
            .`when`().delete("/api/v1/berichten/${b.berichtId}")
            .then().statusCode(204)
    }

    @Test
    fun `DELETE op andermans bericht geeft 403`() {
        val b = insertBericht()
        given()
            .header("X-Ontvanger", andereOntvangerHeader)
            .`when`().delete("/api/v1/berichten/${b.berichtId}")
            .then().statusCode(403)
    }

    @Test
    fun `DELETE door andere ontvanger op al-verwijderd bericht geeft 403 (geen bestaan-onthulling)`() {
        // 403 zowel vóór als ná soft-delete: anders zou een attacker uit het
        // verschil tussen 403 (bestaat, andere ontvanger) en 404 (bestaat niet)
        // het bestaan van berichten kunnen afleiden.
        val b = insertBericht()
        given().header("X-Ontvanger", ontvangerHeader)
            .`when`().delete("/api/v1/berichten/${b.berichtId}")
            .then().statusCode(204)

        given().header("X-Ontvanger", andereOntvangerHeader)
            .`when`().delete("/api/v1/berichten/${b.berichtId}")
            .then().statusCode(403)
    }

    @Test
    fun `DELETE op niet-bestaand bericht geeft 404`() {
        given().header("X-Ontvanger", ontvangerHeader)
            .`when`().delete("/api/v1/berichten/${UUID.randomUUID()}")
            .then().statusCode(404)
    }

    @Test
    fun `DELETE laat bijlage onbereikbaar via GET bijlage (soft-delete isolatie)`() {
        val b = insertBericht()
        val bijlageId = UUID.randomUUID()
        insertBijlageVoor(b.berichtId, bijlageId)

        // Soft-delete het bericht
        given().header("X-Ontvanger", ontvangerHeader)
            .`when`().delete("/api/v1/berichten/${b.berichtId}")
            .then().statusCode(204)

        // De bijlage staat fysiek nog in de DB (geen cascade) maar moet via Ophaal
        // niet meer bereikbaar zijn — anders zou data uit verwijderde berichten lekken.
        given().header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten/${b.berichtId}/bijlagen/$bijlageId")
            .then().statusCode(404)
    }

    @Test
    fun `Soft-deleted bericht verdwijnt uit GET berichten-lijst`() {
        val zichtbaar = insertBericht()
        val teVerwijderen = insertBericht()

        given().header("X-Ontvanger", ontvangerHeader)
            .`when`().delete("/api/v1/berichten/${teVerwijderen.berichtId}")
            .then().statusCode(204)

        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten?page=0&pageSize=10")
            .then()
            .statusCode(200)
            .body("totalElements", `is`(1))
            .body("berichten[0].berichtId", `is`(zichtbaar.berichtId.toString()))
    }

    @Test
    @Transactional
    fun `upsert op ontbrekend bericht gooit NotFoundException`() {
        // Race-condition op repository-niveau: tussen findByBerichtId (in de service)
        // en deze upsert kan het parent-bericht verdwijnen. Dat moet een NotFoundException
        // worden zodat ProblemExceptionMapper er 404 van maakt — niet een gemaskeerde
        // 500 via UncaughtExceptionMapper (functioneel onjuist en niet diagnoseerbaar
        // voor de client).
        val onbekend = UUID.randomUUID()
        assertThrows(NotFoundException::class.java) {
            statusRepository.upsert(
                berichtId = onbekend,
                patch = BerichtStatusPatch(gelezen = true, map = null),
                tijdstip = Instant.now(),
            )
        }
    }

    @Transactional
    fun insertBijlageVoor(berichtId: UUID, bijlageId: UUID) {
        bijlageRepository.save(
            Bijlage(
                bijlageId = bijlageId,
                berichtId = berichtId,
                naam = "audit.pdf",
                mimeType = "application/pdf",
                content = byteArrayOf(1, 2, 3),
            ),
        )
    }
}
