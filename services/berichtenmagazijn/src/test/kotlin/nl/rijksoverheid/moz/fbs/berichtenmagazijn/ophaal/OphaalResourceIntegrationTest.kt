package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
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
    @Inject lateinit var em: EntityManager

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
            .body("berichten[0].inhoud", containsString("Inhoud van"))
            .body("berichten[0]._links.self.href", containsString("/api/v1/berichten/"))
            .body("_links.self.href", containsString("/api/v1/berichten"))
    }

    @Test
    fun `GET berichten batch-laadt bijlage-metadata zonder cross-bericht lekkage`() {
        // Drie berichten met 0/1/2 bijlagen + één soft-deleted bericht met bijlage
        // dat NIET in de lijst mag verschijnen. Dekt BijlageRepository.metadataVoorBerichten
        // (Tuple-projection) tegen een echte DB en bewijst:
        //   - berichten zonder bijlagen krijgen een lege list (geen null/ontbreken)
        //   - per bericht klopt het aantal bijlagen (geen cross-bericht koppeling)
        //   - soft-deleted berichten worden via verwijderdOp IS NULL gefilterd
        val zonder = insertBericht(onderwerp = "Zonder")
        val een = insertBericht(onderwerp = "Een")
        val twee = insertBericht(onderwerp = "Twee")
        val verwijderd = insertBericht(onderwerp = "Verwijderd")
        insertBijlage(een.berichtId, "a".toByteArray())
        insertBijlage(twee.berichtId, "b".toByteArray())
        insertBijlage(twee.berichtId, "c".toByteArray())
        insertBijlage(verwijderd.berichtId, "d".toByteArray())
        markeerVerwijderd(verwijderd.berichtId)

        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten?page=0&pageSize=10")
            .then()
            .statusCode(200)
            .body("totalElements", `is`(3))
            .body("berichten.find { it.berichtId == '${zonder.berichtId}' }.bijlagen", hasSize<Any>(0))
            .body("berichten.find { it.berichtId == '${een.berichtId}' }.bijlagen", hasSize<Any>(1))
            .body("berichten.find { it.berichtId == '${twee.berichtId}' }.bijlagen", hasSize<Any>(2))
    }

    @Transactional
    fun markeerVerwijderd(berichtId: UUID) {
        em.createQuery("UPDATE BerichtEntity b SET b.verwijderdOp = :nu WHERE b.berichtId = :id")
            .setParameter("nu", Instant.now())
            .setParameter("id", berichtId)
            .executeUpdate()
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
    fun `GET bericht met zelfde waarde maar ander type geeft 403`() {
        // BSN en RSIN zijn beide 9-cijferig + elfproef-gevalideerd, dus dezelfde
        // numerieke waarde kan in beide typen voorkomen. BerichtAutorisatie moet
        // op (type, waarde) matchen — anders zou een organisatie met RSIN-hetzelfde
        // -nummer toegang krijgen tot een BSN-bericht (silent data-disclosure).
        // 999993653 voldoet aan elfproef en is dus zowel een geldige BSN als RSIN.
        val b = insertBericht()

        given()
            .header("X-Ontvanger", "RSIN:999993653")
            .`when`().get("/api/v1/berichten/${b.berichtId}")
            .then()
            .statusCode(403)
            .contentType("application/problem+json")
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
    fun `GET bijlage met corrupte MIME-type in DB levert 500 problem+json`() {
        // Simuleert een corrupte / pre-validatie ingeleverde DB-rij: een mimeType die
        // de Bijlage-init wel passeert (niet leeg, max 127 chars) maar geen geldige
        // MediaType is. De resource detecteert dit en gooit een 500 i.p.v. bytes met
        // een ongeldig Content-Type uit te leveren (data-corruptie mag niet stilzwijgend
        // doorgaan).
        val b = insertBericht()
        val bijlageId = insertBijlageMetCorruptMime(b.berichtId)

        given()
            .header("X-Ontvanger", ontvangerHeader)
            .`when`().get("/api/v1/berichten/${b.berichtId}/bijlagen/$bijlageId")
            .then()
            .statusCode(500)
            .contentType("application/problem+json")
    }

    @Transactional
    fun insertBijlageMetCorruptMime(berichtId: UUID): UUID {
        // Direct via native query om Bijlage.init-validatie te omzeilen. Een waarde
        // als "not a media type" is niet leeg en niet te lang, dus de Kotlin-init
        // zou passen — maar `MediaType.valueOf` werpt erop een IllegalArgumentException.
        val berichtDbId: Long = em
            .createQuery("SELECT b.id FROM BerichtEntity b WHERE b.berichtId = :id", java.lang.Long::class.java)
            .setParameter("id", berichtId)
            .singleResult.toLong()
        val bijlageId = UUID.randomUUID()
        em.createNativeQuery(
            "INSERT INTO bijlagen (bijlage_id, bericht_db_id, naam, mime_type, content) " +
                "VALUES (?, ?, ?, ?, ?)",
        )
            .setParameter(1, bijlageId)
            .setParameter(2, berichtDbId)
            .setParameter(3, "corrupt.bin")
            .setParameter(4, "not a media type")
            .setParameter(5, byteArrayOf(1, 2, 3))
            .executeUpdate()
        return bijlageId
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
