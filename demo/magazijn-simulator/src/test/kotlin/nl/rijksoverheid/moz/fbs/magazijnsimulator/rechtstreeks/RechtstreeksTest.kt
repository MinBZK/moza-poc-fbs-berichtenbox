package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.rechtstreeks.TestApp
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.nullValue
import org.jose4j.json.JsonUtil
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.Gedrag
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.GedragModus
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.GesimuleerdeMagazijnen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * De tweede ingang van een gesimuleerd magazijn met de testapp uit de library: `challenge`,
 * `complete`, de afwijzingen die per magazijn gelden, CORS en het contract.
 */
@QuarkusTest
@TestProfile(RechtstreeksTest.Profiel::class)
class RechtstreeksTest {

    /**
     * Vertrouwt een App Manager-sleutel die bij de start ontstaat; een private key hoort niet in git,
     * ook geen testsleutel. Een system property en geen veld: profiel en test kunnen in
     * verschillende classloaders draaien, en dan zou elk zijn eigen sleutel maken.
     */
    class Profiel : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> {
            val jwk = TestApp.jwk(appManager().public as ECPublicKey)
            val sleutel = "simulator.rechtstreeks.app-managers.\"$APP_MANAGER_OIN\".sleutels.\"$KID\""

            return mapOf("$sleutel.x" to jwk["x"] as String, "$sleutel.y" to jwk["y"] as String, "$sleutel.geldig-tot" to "2099-01-01T00:00:00Z")
        }
    }

    // De klok van de simulator, niet die van de test: KlokTest zet met @Priority een vaste klok aan,
    // en die geldt in alle tests van deze module.
    @Inject
    lateinit var klok: Clock

    @Inject
    lateinit var magazijnen: GesimuleerdeMagazijnen

    /** Een app met een eigen sleutel en een credential van de test-App Manager op die sleutel. */
    class App(nu: Instant, val paar: KeyPair = TestApp.sleutelpaar(), claims: Map<String, Any> = emptyMap(), kid: String = KID) {
        val aes: ByteArray = TestApp.aesSleutel()
        val credential: String = TestApp.es256(credentialClaims(paar, nu) + claims, appManager().private, kid)

        /** De body van `complete` voor een nieuwe challenge van magazijn [oin], met de nonce getekend voor [aud]. */
        fun complete(oin: String, aud: String = oin, certificaatSoort: String = "app_manager_jwt_certificate"): Pair<String, Map<String, Any>> {
            val challenge = payload(given().post("${basis(oin)}/rechtstreeks/challenge").then().statusCode(200).extract().asString())

            return voor(challenge, aud, certificaatSoort) to challenge
        }

        /** De body van `complete` op een gegeven challenge. */
        fun voor(challenge: Map<String, Any>, aud: String, certificaatSoort: String = "app_manager_jwt_certificate"): String {
            val nonce = challenge["nonce"] as String

            val presentatie = mapOf(
                "app_nonce_signature" to TestApp.es256(mapOf("sub" to "challenge_response", "aud" to aud, "nonce" to nonce), paar.private),
                "certificate_type" to certificaatSoort,
                "certificate" to credential,
                "session_aes_key" to Base64.getEncoder().encodeToString(aes),
            )
            val versleuteld = TestApp.jweEcdh(JsonUtil.toJson(presentatie), TestApp.publicKeyUitDer(challenge["ephemeral_organization_ec_public_key"] as String))

            return JsonUtil.toJson(mapOf("nonce" to nonce, "encrypted_challenge_result" to versleuteld))
        }

        /** Een magazijnsessie bij [oin]; het `session_token`. */
        fun sessie(oin: String): String {
            val antwoord = given().contentType(ContentType.JSON).body(complete(oin).first).post("${basis(oin)}/rechtstreeks/complete")
                .then().statusCode(200).extract().asString()

            return JsonUtil.parseJson(TestApp.ontsleutelDir(antwoord, aes))["session_token"] as String
        }
    }

    @Test
    fun `een challenge is een JWS met nonce en tijdelijke sleutel, getekend onder de kid van het magazijn`() {
        val jws = given().post("${basis(MAGAZIJN_A)}/rechtstreeks/challenge").then().statusCode(200)
            .contentType("application/jose")
            .header("Cache-Control", "no-store")
            .extract().asString()
        val claims = payload(jws)

        val header = TestApp.header(jws)

        assertEquals(setOf("alg", "typ", "kid"), header.keys)
        assertEquals("ES256", header["alg"])
        assertTrue((header["kid"] as String).startsWith("$MAGAZIJN_A-"), header.toString())
        assertTrue(COMPACT_JWS.matches(jws))
        assertEquals("challenge", claims["sub"])
        assertTrue(Base64.getDecoder().decode(claims["nonce"] as String).size >= 16)
        TestApp.publicKeyUitDer(claims["ephemeral_organization_ec_public_key"] as String)
    }

    @Test
    fun `een geldig credential geeft een magazijnsessie, versleuteld met de AES-sleutel van de app`() {
        val app = App(klok.instant())
        val (body, _) = app.complete(MAGAZIJN_A)

        val antwoord = given().contentType(ContentType.JSON).body(body).post("${basis(MAGAZIJN_A)}/rechtstreeks/complete")
            .then().statusCode(200).contentType("application/jose").extract().asString()
        val sessie = JsonUtil.parseJson(TestApp.ontsleutelDir(antwoord, app.aes))

        // De validator slaat application/jose over; hier staat wat het contract van de payload eist.
        assertTrue(COMPACT_JWE.matches(antwoord))
        assertEquals(setOf("session_token", "expires_in"), sessie.keys)
        assertTrue(Regex("^[A-Za-z0-9]{32}$").matches(sessie["session_token"] as String))
        assertTrue((sessie["expires_in"] as Long) in 1..900)
    }

    @Test
    fun `een afgewezen presentatie geeft 401 met WWW-Authenticate en CORS, en daarna is de nonce verbruikt`() {
        val (body, challenge) = App(klok.instant(), kid = "onbekend").complete(MAGAZIJN_A)

        given().contentType(ContentType.JSON).body(body).post("${basis(MAGAZIJN_A)}/rechtstreeks/complete")
            .then().statusCode(401)
            .contentType("application/problem+json")
            .header("WWW-Authenticate", "Magazijnsessie realm=\"$MAGAZIJN_A\"")
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Expose-Headers", containsString("WWW-Authenticate"))

        // Dezelfde nonce, nu met een presentatie die op zich klopt: de nonce is al verbruikt.
        given().contentType(ContentType.JSON).body(App(klok.instant()).voor(challenge, MAGAZIJN_A)).post("${basis(MAGAZIJN_A)}/rechtstreeks/complete")
            .then().statusCode(401)
    }

    @Test
    fun `een presentatie die niet voor dit magazijn is, of geen credential draagt, wordt afgewezen`() {
        val app = App(klok.instant())

        // De nonce van A is bij B onbekend: elk magazijn heeft zijn eigen nonces.
        given().contentType(ContentType.JSON).body(app.complete(MAGAZIJN_A).first).post("${basis(MAGAZIJN_B)}/rechtstreeks/complete")
            .then().statusCode(401)

        // Een nonce-handtekening voor B, aangeboden bij A: een magazijn dat de presentatie doorspeelt.
        given().contentType(ContentType.JSON).body(app.complete(MAGAZIJN_A, aud = MAGAZIJN_B).first).post("${basis(MAGAZIJN_A)}/rechtstreeks/complete")
            .then().statusCode(401)

        // Self-signed hoort bij de App Manager, niet bij een magazijn.
        given().contentType(ContentType.JSON).body(app.complete(MAGAZIJN_A, certificaatSoort = "certificate_type_self_signed").first)
            .post("${basis(MAGAZIJN_A)}/rechtstreeks/complete")
            .then().statusCode(401)

        // Alleen exp is fout: iat en nbf liggen ervoor, de looptijd is twee uur.
        val nu = klok.instant()
        val verlopen = App(nu.minus(Duration.ofHours(2)), claims = mapOf("exp" to nu.minusSeconds(10).epochSecond))

        given().contentType(ContentType.JSON).body(verlopen.complete(MAGAZIJN_A).first).post("${basis(MAGAZIJN_A)}/rechtstreeks/complete")
            .then().statusCode(401)
    }

    @Test
    fun `een onvolledig verzoek op complete geeft 400 en geen 500, met CORS`() {
        listOf("{}", """{"nonce":"x"}""", "[]", "{kapot").forEach { body ->
            given().contentType(ContentType.JSON).body(body).post("${basis(MAGAZIJN_A)}/rechtstreeks/complete")
                .then().statusCode(400).contentType("application/problem+json").header("Access-Control-Allow-Origin", "*")
        }
    }

    @Test
    fun `een onbekend magazijn geeft 404, ook met CORS, zodat de app de fout kan lezen`() {
        given().post("${basis("00000009000000009999")}/rechtstreeks/challenge")
            .then().statusCode(404).header("Access-Control-Allow-Origin", "*")
    }

    @Test
    fun `een magazijn in storing antwoordt op de tweede ingang volgens het contract`() {
        try {
            listOf(Gedrag(GedragModus.STUK), Gedrag(GedragModus.WEIGERT, foutStatus = 403)).forEach { gedrag ->
                magazijnen.stelGedragBij(MAGAZIJN_C, gedrag)

                valideer(MAGAZIJN_C).post("${basis(MAGAZIJN_C)}/rechtstreeks/challenge")
                    .then().statusCode(503).header("Retry-After", "30").header("Access-Control-Allow-Origin", "*")
            }

            magazijnen.stelGedragBij(MAGAZIJN_C, Gedrag(GedragModus.MALFORMED))

            val onbruikbaar = given().post("${basis(MAGAZIJN_C)}/rechtstreeks/challenge").then().statusCode(200)
                .contentType("application/jose").extract().asString()

            assertFalse(COMPACT_JWS.matches(onbruikbaar))
        } finally {
            magazijnen.herstelGedrag()
        }
    }

    @Test
    fun `een volle challenge-opslag geeft 503 met Retry-After`() {
        // Vult het quotum van deze client bij B voor de levensduur van een nonce (met de vaste klok:
        // de hele testrun). Andere tests vragen daarom geen challenge bij B; ze gebruiken B alleen
        // om een nonce of sessie van A aan te bieden.
        val antwoorden = (1..MAX_CHALLENGES_PER_CLIENT).map {
            given().post("${basis(MAGAZIJN_B)}/rechtstreeks/challenge").then().extract().statusCode()
        }

        assertTrue(antwoorden.all { it == 200 }, antwoorden.toString())

        val vol = valideer(MAGAZIJN_B).post("${basis(MAGAZIJN_B)}/rechtstreeks/challenge").then()
            .statusCode(503).header("Access-Control-Allow-Origin", "*").extract()

        assertTrue(vol.header("Retry-After").toInt() >= 1)
    }

    @Test
    fun `de preflight op de tweede ingang staat Authorization toe, zonder token`() {
        given().header("Origin", "https://app.test")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,content-type")
            .options("${basis(MAGAZIJN_A)}/rechtstreeks/complete")
            .then().statusCode(204)
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Allow-Headers", containsString("Authorization"))
            .header("Access-Control-Allow-Methods", containsString("POST"))
            .header("Access-Control-Max-Age", "600")
            .header("Access-Control-Allow-Credentials", nullValue())
    }

    @Test
    fun `de eerste ingang blijft zonder CORS`() {
        given().header("Origin", "https://app.test").header("X-Ontvanger", "KVK:90000014").get("${basis(MAGAZIJN_A)}/berichten")
            .then().statusCode(200).header("Access-Control-Allow-Origin", nullValue())
        given().header("Origin", "https://app.test")
            .header("Access-Control-Request-Method", "GET")
            .options("${basis(MAGAZIJN_A)}/berichten")
            .then().header("Access-Control-Allow-Origin", nullValue())
    }

    @Test
    fun `challenge, complete en hun foutantwoorden voldoen aan het contract`() {
        val app = App(klok.instant())
        val (body, _) = app.complete(MAGAZIJN_A)

        valideer(MAGAZIJN_A).post("${basis(MAGAZIJN_A)}/rechtstreeks/challenge").then().statusCode(200)
        valideer(MAGAZIJN_A).contentType(ContentType.JSON).body(body).post("${basis(MAGAZIJN_A)}/rechtstreeks/complete").then().statusCode(200)
        valideer(MAGAZIJN_A).contentType(ContentType.JSON).body(body).post("${basis(MAGAZIJN_A)}/rechtstreeks/complete").then().statusCode(401)
        valideer(MAGAZIJN_A).contentType(ContentType.JSON).body("{}").post("${basis(MAGAZIJN_A)}/rechtstreeks/complete").then().statusCode(400)
    }

    /** Toetst alleen de antwoorden: de foutgevallen sturen bewust een verzoek dat niet aan de spec voldoet. */
    private fun valideer(oin: String) = given().filter(
        OpenApiValidationFilter(
            OpenApiInteractionValidator.createForSpecificationUrl("openapi/rechtstreeks-ophalen-api.yaml")
                .withBasePathOverride(basis(oin))
                .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
                .build(),
        ),
    )

    companion object {
        const val MAGAZIJN_A = "00000009000000000001"
        const val MAGAZIJN_B = "00000009000000000002"
        const val MAGAZIJN_C = "00000009000000000003"
        val COMPACT_JWS = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
        val COMPACT_JWE = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
        const val APP_MANAGER_OIN = "00000000000000001100"
        const val KID = "test-1"
        const val MAX_CHALLENGES_PER_CLIENT = 20

        private const val SLEUTEL_PROPERTY = "fbs.test.rechtstreeks.app-manager"

        /** Het sleutelpaar van de test-App Manager, één per JVM. */
        @Synchronized
        fun appManager(): KeyPair {
            val bewaard = System.getProperty(SLEUTEL_PROPERTY)

            if (bewaard != null) {
                val (prive, publiek) = bewaard.split('.').map(Base64.getDecoder()::decode)
                val fabriek = KeyFactory.getInstance("EC")

                return KeyPair(fabriek.generatePublic(X509EncodedKeySpec(publiek)), fabriek.generatePrivate(PKCS8EncodedKeySpec(prive)))
            }

            val nieuw = TestApp.sleutelpaar()
            val codering = Base64.getEncoder()

            System.setProperty(SLEUTEL_PROPERTY, codering.encodeToString(nieuw.private.encoded) + "." + codering.encodeToString(nieuw.public.encoded))

            return nieuw
        }

        fun basis(oin: String) = "/magazijn/$oin/api/v1"

        fun credentialClaims(paar: KeyPair, nu: Instant): Map<String, Any> =
            mapOf(
                "cnf" to mapOf("jwk" to TestApp.jwk(paar.public as ECPublicKey)),
                "scope" to MagazijnVerifiers.SCOPE,
                "iss" to APP_MANAGER_OIN,
                "iat" to nu.epochSecond,
                "nbf" to nu.epochSecond,
                "exp" to nu.plus(Duration.ofHours(1)).epochSecond,
                "ontvanger" to "KVK:90000014",
            )

        fun payload(jws: String): Map<String, Any> = JsonUtil.parseJson(String(Base64.getUrlDecoder().decode(jws.split('.')[1])))
    }
}
