package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import io.restassured.http.Method
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecacheProfile
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.Socket
import java.util.UUID

/**
 * De sessiecontrole vóór elk pad onder `/berichten`. Eén antwoord voor elk soort ongeldige sessie,
 * zodat een aanroeper niet kan aftasten welke id's ooit bestonden; een ongeldige `X-Ontvanger`
 * blijft de 400 van de validatie, want zonder geldige ontvanger valt er geen sessie te toetsen.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class SessieFilterTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    @BeforeEach
    fun reset() {
        sessiecache.reset()
    }

    /** Elk pad onder `/berichten` uit de spec, met een verzoek dat zonder de sessiecontrole zou slagen. */
    class Route(val methode: Method, val pad: String, val body: String? = null) {
        override fun toString() = "$methode $pad"

        fun stuur(verzoek: RequestSpecification): Response {
            body?.let { verzoek.contentType("application/merge-patch+json").body(it) }

            return verzoek.`when`().request(methode, pad)
        }
    }

    companion object {
        private const val ONTVANGER = "BSN:999990019"
        private const val MAGAZIJN = "00000001003214345000"
        private val ID = UUID.fromString("0b0e5a52-8a8f-4d5f-9d2e-2f7f7a0c1d11")

        @JvmStatic
        fun routes(): List<Route> = listOf(
            Route(Method.GET, "/api/v1/berichten"),
            Route(Method.GET, "/api/v1/berichten/_ophalen"),
            Route(Method.GET, "/api/v1/berichten/_volgen"),
            Route(Method.GET, "/api/v1/berichten/_zoeken?q=rente"),
            Route(Method.GET, "/api/v1/berichten/$ID?magazijnId=$MAGAZIJN"),
            Route(Method.PATCH, "/api/v1/berichten/$ID?magazijnId=$MAGAZIJN", """{"status":"gelezen"}"""),
            Route(Method.DELETE, "/api/v1/berichten/$ID?magazijnId=$MAGAZIJN"),
            Route(Method.GET, "/api/v1/berichten/$ID/bijlagen/${UUID.randomUUID()}?magazijnId=$MAGAZIJN"),
        )

        @JvmStatic
        fun krommeHeaders(): List<Arguments> = routes().flatMap { route ->
            listOf(
                Arguments.of(route, "dezelfde X-Ontvanger twee keer", listOf(ONTVANGER, ONTVANGER)),
                Arguments.of(route, "twee verschillende X-Ontvangers", listOf(ONTVANGER, "BSN:999993653")),
                Arguments.of(route, "geen X-Ontvanger", emptyList<String>()),
                // Voldoet aan het patroon, maar niet aan de elfproef.
                Arguments.of(route, "een BSN zonder elfproef", listOf("BSN:123456789")),
            )
        }

        @JvmStatic
        fun ongeldigeSessies(): List<Arguments> = routes().flatMap { route ->
            listOf(
                Arguments.of(route, null),
                Arguments.of(route, ""),
                Arguments.of(route, "te-kort"),
                Arguments.of(route, "A".repeat(23)),
                Arguments.of(route, "A".repeat(21) + "!"),
                // Goed gevormd, maar nooit gestart.
                Arguments.of(route, "A".repeat(22)),
            )
        }
    }

    @ParameterizedTest(name = "{0} met X-Sessie ''{1}''")
    @MethodSource("ongeldigeSessies")
    fun `zonder geldige X-Sessie volgt 401 sessie-ongeldig`(route: Route, sessie: String?) {
        val verzoek = given().header("X-Ontvanger", ONTVANGER)

        sessie?.let { verzoek.header("X-Sessie", it) }

        route.stuur(verzoek)
            .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("type", equalTo("urn:fbs:fout:sessie-ongeldig"))

        assertTrue(sessiecache.verwijderAanroepen.isEmpty() && sessiecache.werkBijAanroepen == 0, "geen schrijfactie zonder sessie")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    fun `een sessie van een andere ontvanger geeft 401`(route: Route) {
        val vanAnder = sessiecache.sessieVoor("BSN:999993653")

        route.stuur(given().header("X-Ontvanger", ONTVANGER).header("X-Sessie", vanAnder))
            .then()
            .statusCode(401)
            .body("type", equalTo("urn:fbs:fout:sessie-ongeldig"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    fun `met een geldige sessie wordt die sessie geraakt en gaat het verzoek door`(route: Route) {
        val sessie = sessiecache.sessieVoor(ONTVANGER)
        // Geen gegevens en geen magazijn in deze test: een 4xx/5xx van verderop is prima, een 401 niet.
        val status = route.stuur(given().header("X-Ontvanger", ONTVANGER).header("X-Sessie", sessie))
            .then()
            .extract()
            .statusCode()

        assertNotEquals(401, status, "$route werd geweigerd met een geldige sessie")
        assertEquals(listOf(sessie), sessiecache.geraakteSessies.toList())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    fun `een ongeldige X-Ontvanger blijft een 400, ook zonder sessie`(route: Route) {
        route.stuur(given().header("X-Ontvanger", "GEENTYPE:123"))
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("type", equalTo("urn:fbs:fout:ongeldig-verzoek"))

        assertTrue(sessiecache.geraakteSessies.isEmpty())
    }

    /**
     * Elk geval waarin de headers niet precies één bruikbare waarde dragen. Een samengevoegde
     * `X-Ontvanger` ("a,b") liet de controle eerder door, terwijl de resource de eerste waarde nam.
     */
    @ParameterizedTest(name = "{0} met {1}")
    @MethodSource("krommeHeaders")
    fun `een X-Ontvanger die niet precies een geldige waarde is, geeft 400 zonder de sessie te raken`(
        route: Route,
        omschrijving: String,
        ontvangers: List<String>,
    ) {
        val verzoek = given().header("X-Sessie", "A".repeat(22))

        ontvangers.forEach { verzoek.header("X-Ontvanger", it) }

        route.stuur(verzoek)
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("type", equalTo("urn:fbs:fout:ongeldig-verzoek"))

        assertTrue(sessiecache.geraakteSessies.isEmpty(), omschrijving)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    fun `een dubbele X-Sessie geeft 401, ook als beide waarden een lopende sessie zijn`(route: Route) {
        val sessie = sessiecache.sessieVoor(ONTVANGER)

        route.stuur(given().header("X-Ontvanger", ONTVANGER).header("X-Sessie", sessie).header("X-Sessie", sessie))
            .then()
            .statusCode(401)
            .body("type", equalTo("urn:fbs:fout:sessie-ongeldig"))

        assertTrue(sessiecache.geraakteSessies.isEmpty())
    }

    @Test
    fun `een onbereikbare opslag bij het raken geeft 503 en geen 401`() {
        val sessie = sessiecache.sessieVoor(ONTVANGER)
        sessiecache.raakFout = SessiecacheException.Onbereikbaar("Redis weg")

        // Een storing is geen ongeldige sessie: met een 401 zou de berichtenbox de gebruiker uitloggen.
        given()
            .header("X-Ontvanger", ONTVANGER)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(503)
            .header("Retry-After", "30")
            .body("type", equalTo("urn:fbs:fout:tijdelijk-niet-beschikbaar"))
    }

    @Test
    fun `aanmeldingen en de specificatie vragen geen sessie`() {
        given().`when`().get("/api/v1/openapi.json").then().statusCode(200)
        given().`when`().get("/openapi.json").then().statusCode(200)

        // Een kromme aanmelding: wat er ook misgaat, het is geen sessiefout.
        val aanmelding = given()
            .contentType("application/cloudevents+json")
            .body("{}")
            .`when`()
            .post("/api/v1/aanmeldingen")
            .then()
            .extract()

        assertNotEquals(401, aanmelding.statusCode())
        assertTrue(sessiecache.geraakteSessies.isEmpty())
    }

    @Test
    fun `een omweg via een vrijgesteld pad omzeilt de sessiecontrole niet`() {
        // Ruw over de socket: een HTTP-client lost `..` vaak al zelf op, en dan toetst de test niets.
        // Met een goed gevormde maar nooit gestarte sessie: alleen de sessiecontrole kan dit dan
        // weigeren, dus elke andere status dan 401 betekent dat de omweg erlangs kwam.
        val status = Socket("localhost", RestAssured.port).use { socket ->
            socket.getOutputStream().write(
                (
                    "GET /api/v1/aanmeldingen/../berichten HTTP/1.1\r\n" +
                        "Host: localhost\r\nX-Ontvanger: $ONTVANGER\r\nX-Sessie: ${"A".repeat(22)}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )

            socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
        }

        assertEquals(401, status)
    }
}
