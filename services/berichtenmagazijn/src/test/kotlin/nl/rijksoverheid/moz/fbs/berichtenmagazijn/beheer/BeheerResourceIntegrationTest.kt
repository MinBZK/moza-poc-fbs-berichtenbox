package nl.rijksoverheid.moz.fbs.berichtenmagazijn.beheer

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import org.hamcrest.Matchers.`is`
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
    fun `DELETE op al-verwijderd bericht geeft 404 (idempotent vanuit clientzicht)`() {
        val b = insertBericht()
        given().header("X-Ontvanger", ontvangerHeader)
            .`when`().delete("/api/v1/berichten/${b.berichtId}")
            .then().statusCode(204)

        given().header("X-Ontvanger", ontvangerHeader)
            .`when`().delete("/api/v1/berichten/${b.berichtId}")
            .then().statusCode(404)
    }

    @Test
    fun `DELETE op andermans bericht geeft 403`() {
        val b = insertBericht()
        given()
            .header("X-Ontvanger", andereOntvangerHeader)
            .`when`().delete("/api/v1/berichten/${b.berichtId}")
            .then().statusCode(403)
    }
}
