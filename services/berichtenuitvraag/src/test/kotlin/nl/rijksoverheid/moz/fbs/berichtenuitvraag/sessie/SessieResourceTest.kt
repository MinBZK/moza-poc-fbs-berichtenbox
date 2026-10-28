package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecacheProfile
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.Socket
import java.time.Instant

/**
 * `POST /sessies` en `DELETE /sessies/huidige`: de vertaling van de facade naar HTTP. Het
 * sessiegedrag zelf (verloop, opruimen) zit in de library; de mock spiegelt alleen wat de uitvraag
 * ervan ziet.
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
    fun `start geeft 201 met sessieId, geldigTot, uiterlijkGeldigTot en een Location zonder geheim`() {
        val antwoord = given().contentType("application/json")
            .header("X-Ontvanger", ONTVANGER)
            .`when`()
            .post("/api/v1/sessies")
            .then()
            .statusCode(201)
            .contentType("application/json")
            .header("API-Version", notNullValue())
            .header("Location", "/api/v1/sessies/huidige")
            .body("sessieId", matchesPattern("^[A-Za-z0-9_-]{22}$"))
            .body("_links.self.href", equalTo("/api/v1/sessies/huidige"))
            .extract()
            .jsonPath()

        val sessieId = antwoord.getString("sessieId")
        val geldigheid = sessiecache.geldigheden.getValue(sessieId)

        assertEquals(Bsn("999990019"), sessiecache.sessies[sessieId], "de sessie hoort bij de ontvanger uit X-Ontvanger")
        assertEquals(geldigheid.geldigTot, Instant.parse(antwoord.getString("geldigTot")))
        assertEquals(geldigheid.uiterlijkGeldigTot, Instant.parse(antwoord.getString("uiterlijkGeldigTot")))
        assertTrue(geldigheid.geldigTot < geldigheid.uiterlijkGeldigTot, "het verloop bij inactiviteit hoort vóór het plafond")
    }

    /**
     * Zoals een browser of `curl -X POST` hem stuurt: zonder body en dus zonder `Content-Type`. Ruw
     * over de socket, want REST Assured zet bij een POST zelf een formulier-mediatype.
     */
    @Test
    fun `start zonder body en zonder Content-Type geeft 201`() {
        val statusregel = Socket("localhost", RestAssured.port).use { socket ->
            socket.getOutputStream().write(
                (
                    "POST /api/v1/sessies HTTP/1.1\r\nHost: localhost\r\nX-Ontvanger: $ONTVANGER\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
            )

            socket.getInputStream().bufferedReader().readLine()
        }

        assertEquals("201", statusregel.split(' ')[1], statusregel)
    }

    @Test
    fun `een gestarte sessie opent de berichten, en na het beeindigen niet meer`() {
        val sessieId = given().contentType("application/json").header("X-Ontvanger", ONTVANGER).post("/api/v1/sessies")
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
    fun `een onbereikbare opslag bij het starten geeft 503 met Retry-After`() {
        sessiecache.startSessieFout = SessiecacheException.Onbereikbaar("Redis weg")

        given().contentType("application/json")
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
        given().contentType("application/json")
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
        given().contentType("application/json").`when`().post("/api/v1/sessies").then().statusCode(400).contentType("application/problem+json")
    }

    // Zonder sessie-id is er geen sessie aangewezen: een ontbrekend credential, geen idempotent einde.
    @ParameterizedTest(name = "X-Sessie ''{0}''")
    @ValueSource(strings = ["", "te-kort", "AAAAAAAAAAAAAAAAAAAAAA!"])
    fun `einde zonder bruikbare X-Sessie geeft 401 en beeindigt niets`(sessie: String) {
        val lopend = sessiecache.sessieVoor(ONTVANGER)
        val verzoek = given().header("X-Ontvanger", ONTVANGER)

        if (sessie.isNotEmpty()) verzoek.header("X-Sessie", sessie)

        verzoek.`when`()
            .delete("/api/v1/sessies/huidige")
            .then()
            .statusCode(401)
            .header("WWW-Authenticate", CHALLENGE)
            .body("type", equalTo("urn:fbs:fout:sessie-ongeldig"))

        assertEquals(Bsn("999990019"), sessiecache.sessies[lopend])
    }

    private fun lijst(sessieId: String) =
        given().header("X-Ontvanger", ONTVANGER).header("X-Sessie", sessieId).`when`().get("/api/v1/berichten")

    private fun beeindig(sessieId: String) =
        given().header("X-Ontvanger", ONTVANGER).header("X-Sessie", sessieId).`when`().delete("/api/v1/sessies/huidige")

    private companion object {
        const val ONTVANGER = "BSN:999990019"
        const val CHALLENGE = "X-Sessie realm=\"berichtenuitvraag\""
    }
}
