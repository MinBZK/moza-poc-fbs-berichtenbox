package nl.rijksoverheid.moz.fbs.appmanager

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import io.restassured.config.EncoderConfig
import io.restassured.http.ContentType
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.rechtstreeks.TestApp
import org.hamcrest.Matchers.containsString
import org.jose4j.json.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.util.Base64

/** Het hele activeren zoals een app het doorloopt, met de app-kant uit de tests van de library. */
@QuarkusTest
class ActiverenTest {

    @Inject
    lateinit var sleutel: Ondertekensleutel

    companion object {

        /** Een JWS of JWE is tekst; RestAssured kent `application/jose` niet uit zichzelf. */
        @JvmStatic
        @BeforeAll
        fun joseAlsTekst() {
            RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/jose", ContentType.TEXT),
            )
        }
    }

    /** Een app met een eigen sleutel en, na `complete`, een sessie bij de App Manager. */
    inner class App(val paar: KeyPair = TestApp.sleutelpaar()) {

        val aes: ByteArray = TestApp.aesSleutel()
        lateinit var sessieToken: String

        fun maakSessie(): App {
            val challenge = given().post("/v1/challenge").then().statusCode(200).extract().asString()
            val claims = TestApp.verifieerJws(challenge, sleutel.publiek)
            val nonce = claims["nonce"] as String
            val tijdelijk = TestApp.publicKeyUitDer(claims["ephemeral_organization_ec_public_key"] as String)
            val jwk = TestApp.jwk(paar.public as ECPublicKey)

            val presentatie = mapOf(
                "app_nonce_signature" to TestApp.es256(mapOf("sub" to "challenge_response", "aud" to AppManagerConfig.DEMO_OIN, "nonce" to nonce), paar.private),
                "certificate_type" to "certificate_type_self_signed",
                "certificate" to TestApp.es256(mapOf("sub" to "certificate_type_self_signed", "cnf" to mapOf("jwk" to jwk)), paar.private),
                "session_aes_key" to Base64.getEncoder().encodeToString(aes),
            )

            val antwoord = given().contentType(ContentType.JSON)
                .body(mapOf("nonce" to nonce, "encrypted_challenge_result" to TestApp.jweEcdh(JsonUtil.toJson(presentatie), tijdelijk)))
                .post("/v1/complete")
                .then().statusCode(200).extract().asString()

            sessieToken = JsonUtil.parseJson(TestApp.ontsleutelDir(antwoord, aes))["session_token"] as String

            return this
        }

        /** Stap 3 en de keuze: geeft het `registration_token` terug zoals de app het in het fragment vindt. */
        fun kies(persona: String): String {
            val koppeling = given().redirects().follow(false)
                .queryParam("session_token", sessieToken)
                .get("/register_app")
                .then().statusCode(303).header("Location", containsString("/register_app"))
                .extract().cookie(ActiverenResource.COOKIE)

            val locatie = given().redirects().follow(false)
                .cookie(ActiverenResource.COOKIE, koppeling)
                .formParam("persona", persona)
                .post("/register_app/kies")
                .then().statusCode(303).extract().header("Location")

            assertTrue(locatie.startsWith("https://app.test/#registration_token="), locatie)

            val jwe = URLDecoder.decode(locatie.substringAfter("#registration_token="), Charsets.UTF_8)
            val bericht = JsonUtil.parseJson(TestApp.ontsleutelDir(jwe, aes))

            assertEquals("registration_token", bericht["sub"])

            return bericht["registration_token"] as String
        }

        fun credential(registratie: String, iss: String = ActiverenResource.APP_ISS) = given()
            .header("Authorization", sessieToken)
            .contentType("application/jose")
            .body(TestApp.jweDir(JsonUtil.toJson(mapOf("sub" to "registration_token", "iss" to iss, "registration_token" to registratie)), aes))
            .post("/credential")
    }

    @Test
    fun `een app die een persona kiest, krijgt een credential voor die ondernemer op zijn eigen sleutel`() {
        val app = App().maakSessie()
        val registratie = app.kies("vandijk")

        val antwoord = app.credential(registratie).then().statusCode(200).extract().asString()
        val vc = TestApp.ontsleutelDir(antwoord, app.aes)
        val claims = TestApp.verifieerJws(vc, sleutel.publiek)
        val cnf = (claims["cnf"] as Map<*, *>)["jwk"] as Map<*, *>

        assertEquals(mapOf("alg" to "ES256", "typ" to "jwt", "kid" to "app-manager-1"), TestApp.header(vc))
        assertEquals(AppManagerConfig.DEMO_OIN, claims["iss"])
        assertEquals("KVK:90000014", claims["ontvanger"])
        assertEquals(AppManagerConfig.SCOPE, claims["scope"])
        assertEquals(3600L, (claims["exp"] as Long) - (claims["iat"] as Long))
        assertEquals(TestApp.jwk(app.paar.public as ECPublicKey)["x"], cnf["x"])
    }

    @Test
    fun `met hetzelfde registration_token haalt dezelfde sleutel later een vers credential op`() {
        val eerste = App().maakSessie()
        val registratie = eerste.kies("pietersen")
        val later = App(eerste.paar).maakSessie()

        later.credential(registratie).then().statusCode(200)
    }

    @Test
    fun `een gestolen registration_token levert geen credential op een andere sleutel`() {
        val registratie = App().maakSessie().kies("vandijk")

        App().maakSessie().credential(registratie).then().statusCode(401).header("WWW-Authenticate", "Magazijnsessie")
    }

    @Test
    fun `een verzoek om een credential met een verkeerde iss, onbekend token of onzin wordt afgewezen`() {
        val app = App().maakSessie()
        val registratie = app.kies("vandijk")

        app.credential(registratie, iss = "https://aanvaller.example/").then().statusCode(401)
        app.credential("onbekend").then().statusCode(401)
        given().header("Authorization", app.sessieToken).contentType("application/jose")
            .body(TestApp.jweDir("geen json", app.aes)).post("/credential").then().statusCode(401)
        given().contentType("application/jose").body("x").post("/credential").then().statusCode(401)
    }

    @Test
    fun `het session_token in de URL werkt één keer`() {
        val app = App().maakSessie()

        app.kies("vandijk")

        given().redirects().follow(false).queryParam("session_token", app.sessieToken).get("/register_app").then().statusCode(401)
    }

    @Test
    fun `een koppeling werkt voor één keuze, en zonder koppeling is er geen keuzepagina`() {
        val app = App().maakSessie()
        val koppeling = given().redirects().follow(false).queryParam("session_token", app.sessieToken)
            .get("/register_app").then().statusCode(303).extract().cookie(ActiverenResource.COOKIE)

        given().cookie(ActiverenResource.COOKIE, koppeling).get("/register_app")
            .then().statusCode(200)
            .header("Content-Security-Policy", containsString("form-action 'self' https://app.test"))
            .body(containsString("Garage Van Dijk B.V."))

        given().redirects().follow(false).cookie(ActiverenResource.COOKIE, koppeling).formParam("persona", "vandijk")
            .post("/register_app/kies").then().statusCode(303)
        given().redirects().follow(false).cookie(ActiverenResource.COOKIE, koppeling).formParam("persona", "vandijk")
            .post("/register_app/kies").then().statusCode(401)
        given().get("/register_app").then().statusCode(401)
        given().cookie(ActiverenResource.COOKIE, "onbekend").get("/register_app").then().statusCode(401)
    }

    @Test
    fun `een onbekende persona geeft 400 en laat de koppeling staan`() {
        val app = App().maakSessie()
        val koppeling = given().redirects().follow(false).queryParam("session_token", app.sessieToken)
            .get("/register_app").then().extract().cookie(ActiverenResource.COOKIE)

        given().cookie(ActiverenResource.COOKIE, koppeling).formParam("persona", "bestaat-niet").post("/register_app/kies").then().statusCode(400)
        given().redirects().follow(false).cookie(ActiverenResource.COOKIE, koppeling).formParam("persona", "vandijk")
            .post("/register_app/kies").then().statusCode(303)
    }

    @Test
    fun `een onvolledige of ongeldige presentatie wordt afgewezen`() {
        given().contentType(ContentType.JSON).body(mapOf("nonce" to "x")).post("/v1/complete")
            .then().statusCode(401).contentType("application/problem+json")
        given().contentType(ContentType.JSON).body("{}").post("/v1/complete").then().statusCode(401)
        given().contentType(ContentType.JSON).body(mapOf("nonce" to "x", "encrypted_challenge_result" to "y")).post("/v1/complete")
            .then().statusCode(401)
    }

    @Test
    fun `een app van een andere origin mag de protocolpaden aanroepen, met Authorization`() {
        given().header("Origin", "https://app.test")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,content-type")
            .options("/credential")
            .then().statusCode(200)
            .header("Access-Control-Allow-Origin", containsString("app.test"))
            .header("Access-Control-Allow-Headers", containsString("Authorization"))
    }
}
