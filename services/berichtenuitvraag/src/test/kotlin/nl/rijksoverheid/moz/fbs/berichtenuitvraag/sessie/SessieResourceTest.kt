package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecacheProfile
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

/**
 * `POST /sessies` en `DELETE /sessies/huidige`: de vertaling van de facade naar HTTP. Het
 * sessiegedrag zelf (verloop, opruimen, de grens) zit in de library; de mock spiegelt alleen wat
 * de uitvraag ervan ziet.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class SessieResourceTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    @BeforeEach
    fun reset() {
        sessiecache.reset()
    }

    @Test
    fun `start geeft 201 met sessieId en uiterlijkGeldigTot`() {
        val antwoord = given()
            .header("X-Ontvanger", ONTVANGER)
            .`when`()
            .post("/api/v1/sessies")
            .then()
            .statusCode(201)
            .contentType("application/json")
            .header("API-Version", notNullValue())
            // Het id is een geheim: het hoort in geen URL te landen, ook niet in een Location.
            .header("Location", nullValue())
            .body("sessieId", matchesPattern("^[A-Za-z0-9_-]{22}$"))
            .extract()
            .jsonPath()

        val sessieId = antwoord.getString("sessieId")

        assertEquals(Bsn("999990019"), sessiecache.sessies[sessieId], "de sessie hoort bij de ontvanger uit X-Ontvanger")
        assertTrue(Instant.parse(antwoord.getString("uiterlijkGeldigTot")).isAfter(Instant.now()))
    }

    @Test
    fun `een gestarte sessie opent de berichten, en na het beeindigen niet meer`() {
        val sessieId = given().header("X-Ontvanger", ONTVANGER).post("/api/v1/sessies")
            .then().statusCode(201).extract().path<String>("sessieId")

        lijst(sessieId).then().statusCode(200)

        beeindig(sessieId).then().statusCode(204)

        lijst(sessieId).then().statusCode(401).body("type", equalTo("urn:fbs:fout:sessie-ongeldig"))
    }

    @Test
    fun `einde geeft 204, ook twee keer`() {
        val sessieId = sessiecache.sessieVoor(ONTVANGER)

        beeindig(sessieId).then().statusCode(204)
        beeindig(sessieId).then().statusCode(204)
    }

    @Test
    fun `einde van een onbekende of andermans sessie geeft 204 en laat die sessie staan`() {
        val vanAnder = sessiecache.sessieVoor("BSN:999993653")

        beeindig("B".repeat(22)).then().statusCode(204)
        beeindig(vanAnder).then().statusCode(204)

        assertEquals(Bsn("999993653"), sessiecache.sessies[vanAnder])
    }

    @Test
    fun `zesde start geeft 429, en na het beeindigen van een kan het weer`() {
        val sessies = (1..5).map { _ ->
            given().header("X-Ontvanger", ONTVANGER).post("/api/v1/sessies")
                .then().statusCode(201).extract().path<String>("sessieId")
        }

        given()
            .header("X-Ontvanger", ONTVANGER)
            .`when`()
            .post("/api/v1/sessies")
            .then()
            .statusCode(429)
            .contentType("application/problem+json")
            .body("type", equalTo("urn:fbs:fout:te-veel-sessies"))

        // Een andere ontvanger heeft zijn eigen grens.
        given().header("X-Ontvanger", "BSN:999993653").post("/api/v1/sessies").then().statusCode(201)

        beeindig(sessies.first()).then().statusCode(204)

        given().header("X-Ontvanger", ONTVANGER).post("/api/v1/sessies").then().statusCode(201)
    }

    @Test
    fun `een onbereikbare opslag bij het starten geeft 503 met Retry-After`() {
        sessiecache.startSessieFout = SessiecacheException.Onbereikbaar("Redis weg")

        given()
            .header("X-Ontvanger", ONTVANGER)
            .`when`()
            .post("/api/v1/sessies")
            .then()
            .statusCode(503)
            .header("Retry-After", "30")
            .body("type", equalTo("urn:fbs:fout:tijdelijk-niet-beschikbaar"))
    }

    @ParameterizedTest(name = "X-Ontvanger ''{0}''")
    @ValueSource(strings = ["", "BSN-999990019", "GEENTYPE:123", "BSN:123456789"])
    fun `start met een ongeldige X-Ontvanger geeft 400`(ontvanger: String) {
        given()
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .post("/api/v1/sessies")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")

        assertTrue(sessiecache.sessies.isEmpty())
    }

    @Test
    fun `start zonder X-Ontvanger geeft 400`() {
        given().`when`().post("/api/v1/sessies").then().statusCode(400).contentType("application/problem+json")
    }

    @Test
    fun `einde zonder X-Sessie geeft 400`() {
        given()
            .header("X-Ontvanger", ONTVANGER)
            .`when`()
            .delete("/api/v1/sessies/huidige")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    private fun lijst(sessieId: String) =
        given().header("X-Ontvanger", ONTVANGER).header("X-Sessie", sessieId).`when`().get("/api/v1/berichten")

    private fun beeindig(sessieId: String) =
        given().header("X-Ontvanger", ONTVANGER).header("X-Sessie", sessieId).`when`().delete("/api/v1/sessies/huidige")

    private companion object {
        const val ONTVANGER = "BSN:999990019"
    }
}
