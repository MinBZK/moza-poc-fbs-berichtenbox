package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Het publieke gedrag van de dienst, met de wegwerpketen van de testmodus. De keten tot een
 * vastgelegde root staat in [StelseldocumentKetenTest], dat een eigen keystore meegeeft.
 */
@QuarkusTest
class StelseldocumentApiTest {

    @Inject
    lateinit var uitgegeven: UitgegevenStelseldocument

    private val pad = "/api/v1/stelseldocument"

    private fun klokOp(moment: Instant) {
        QuarkusMock.installMockForType(Clock.fixed(moment, ZoneOffset.UTC), Clock::class.java)
    }

    @BeforeEach
    fun verseUitgifte() {
        uitgegeven.ververs()
    }

    @Test
    fun `het document komt als compacte JWS met zijn eigen mediatype en cache-regels`() {
        given()
            .`when`().get(pad)
            .then()
            .statusCode(200)
            .contentType("application/jose")
            .header("API-Version", "0.1.0")
            .header("Cache-Control", "public, max-age=300")
            .header("ETag", startsWith("W/\""))
            .body(matchesPattern("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$"))
    }

    @Test
    fun `het document bevat de magazijnen uit het register en de geconfigureerde uitgever`() {
        val payload = Afnemer.payload(given().get(pad).then().statusCode(200).extract().asString())

        assertEquals(
            listOf("00000000000000100000" to "RVO", "00000001823288444000" to "Belastingdienst"),
            payload.path("organizations").map { it.path("oin").asText() to it.path("name").asText() },
        )
        assertEquals("http://localhost:8090", payload.path("organizations")[0].path("magazijn_url").asText())
        assertEquals("00000000000000001000", payload.path("iss").asText())
        assertEquals("test", payload.path("environment").asText())
        assertEquals(24 * 3600L, payload.path("exp").asLong() - payload.path("iat").asLong())
    }

    @Test
    fun `een HEAD levert dezelfde headers zonder body`() {
        given()
            .`when`().head(pad)
            .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("Cache-Control", "public, max-age=300")
            .body(equalTo(""))
    }

    @Test
    fun `If-None-Match met de huidige ETag geeft 304 zonder body, met de cache-headers`() {
        val etag = given().get(pad).then().extract().header("ETag")

        given()
            .header("If-None-Match", etag)
            .`when`().get(pad)
            .then()
            .statusCode(304)
            .header("ETag", etag)
            .header("Cache-Control", "public, max-age=300")
            .header("API-Version", "0.1.0")
            .body(equalTo(""))
    }

    @Test
    fun `If-None-Match met een oude ETag geeft het document`() {
        given()
            .header("If-None-Match", "W/\"verouderd\"")
            .`when`().get(pad)
            .then()
            .statusCode(200)
            .contentType("application/jose")
    }

    @Test
    fun `een ververst exemplaar heeft een latere iat, dezelfde versie en een andere ETag`() {
        val eerste = given().get(pad).then().extract()

        // Echte tijd in plaats van een verzette klok: de bean is gedeeld met de andere testklassen
        // in dezelfde Quarkus-instantie, en een exemplaar uit de toekomst blijft daar staan — de
        // dienst vervangt een exemplaar bewust niet door een ouder.
        Thread.sleep(1100)
        uitgegeven.ververs()

        val tweede = given().get(pad).then().statusCode(200).extract()
        val voor = Afnemer.payload(eerste.asString())
        val na = Afnemer.payload(tweede.asString())

        assertTrue(na.path("iat").asLong() > voor.path("iat").asLong())
        assertEquals(voor.path("version").asText(), na.path("version").asText())
        assertNotEquals(eerste.header("ETag"), tweede.header("ETag"))

        given().header("If-None-Match", eerste.header("ETag")).get(pad).then().statusCode(200)
    }

    @Test
    fun `een Accept die application-jose uitsluit geeft 406 als problem-json`() {
        given()
            .accept("application/json")
            .`when`().get(pad)
            .then()
            .statusCode(406)
            .contentType("application/problem+json")
            .header("API-Version", "0.1.0")
            .body("type", equalTo("urn:fbs:fout:ongeldig-verzoek"))
            .body("status", equalTo(406))
    }

    @ParameterizedTest
    @ValueSource(strings = ["application/jose", "*/*", "application/*", "text/html, application/jose;q=0.5"])
    fun `een Accept die application-jose toelaat krijgt het document`(accept: String) {
        given().accept(accept).`when`().get(pad).then().statusCode(200).contentType("application/jose")
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "PUT", "DELETE", "PATCH"])
    fun `een andere methode dan GET geeft 405 als problem-json`(methode: String) {
        given()
            .`when`().request(methode, pad)
            .then()
            .statusCode(405)
            .contentType("application/problem+json")
            .header("API-Version", "0.1.0")
            .body("type", equalTo("urn:fbs:fout:ongeldig-verzoek"))
            .body("status", equalTo(405))
    }

    @Test
    fun `een onbekend pad onder de API geeft 404 als problem-json`() {
        given()
            .`when`().get("/api/v1/bestaat-niet")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("type", equalTo("urn:fbs:fout:niet-gevonden"))
    }

    // De spec belooft problem+json voor de API. Daarbuiten antwoordt de HTTP-laag zelf.
    @Test
    fun `een onbekend pad buiten de API geeft 404 zonder problem-json`() {
        given()
            .`when`().get("/bestaat-niet")
            .then()
            .statusCode(404)
            .contentType(not(containsString("problem+json")))
    }

    @Test
    fun `zonder onverlopen document volgt 503 met Retry-After en meldt de dienst zich niet gereed`() {
        klokOp(Instant.now().plus(Duration.ofHours(25)))

        given()
            .`when`().get(pad)
            .then()
            .statusCode(503)
            .contentType("application/problem+json")
            .header("Retry-After", "60")
            .header("Cache-Control", "no-store")
            .body("type", equalTo("urn:fbs:fout:tijdelijk-niet-beschikbaar"))

        given()
            .`when`().get("/q/health/ready")
            .then()
            .statusCode(503)
            .body("checks.find { it.name == 'stelseldocument' }.status", equalTo("DOWN"))
    }

    @Test
    fun `met een geldig document meldt de dienst zich gereed zonder iets over de sleutel te zeggen`() {
        given()
            .`when`().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("checks.find { it.name == 'stelseldocument' }.status", equalTo("UP"))
            .body("checks.find { it.name == 'stelseldocument' }.data", nullValue())
    }

    @Test
    fun `een browser van een andere origin mag het document lezen, zonder credentials`() {
        given()
            .header("Origin", "https://app.example")
            .`when`().get(pad)
            .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", "https://app.example")
            .header("Access-Control-Expose-Headers", containsString("ETag"))
            .header("Access-Control-Expose-Headers", containsString("API-Version"))
            .header("Access-Control-Allow-Credentials", not(equalTo("true")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/stelseldocument", "/.well-known/jwks.json", "/openapi.json"])
    fun `de preflight voor een conditioneel verzoek slaagt en mag bewaard worden`(doel: String) {
        given()
            .header("Origin", "https://app.example")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "If-None-Match")
            .`when`().options(doel)
            .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", "https://app.example")
            .header("Access-Control-Allow-Methods", containsString("GET"))
            .header("Access-Control-Allow-Headers", containsString("If-None-Match"))
            .header("Access-Control-Max-Age", "3600")
            .header("Access-Control-Allow-Credentials", not(equalTo("true")))
    }

    @Test
    fun `een preflight voor een schrijvende methode wordt niet toegestaan`() {
        given()
            .header("Origin", "https://app.example")
            .header("Access-Control-Request-Method", "DELETE")
            .`when`().options(pad)
            .then()
            .header("Access-Control-Allow-Methods", not(containsString("DELETE")))
    }

    @Test
    fun `de spec staat zonder authenticatie op openapi-json`() {
        given()
            .`when`().get("/openapi.json")
            .then()
            .statusCode(200)
            .body("paths.'/api/v1/stelseldocument'.get", notNullValue())
    }
}
