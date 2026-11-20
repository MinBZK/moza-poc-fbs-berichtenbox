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
import io.restassured.specification.RequestSpecification
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
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * De tweede ingang van een gesimuleerd magazijn met de testapp uit de library: `challenges`,
 * `sessies`, de afwijzingen die per magazijn gelden, CORS en het contract.
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

    // De klok van de simulator, niet die van de test: die staat stil (VasteKlokProducer).
    @Inject
    lateinit var klok: Clock

    @Inject
    lateinit var magazijnen: GesimuleerdeMagazijnen

    /** Een app met een eigen sleutel en een credential van de test-App Manager op die sleutel. */
    class App(
        nu: Instant,
        val paar: KeyPair = TestApp.sleutelpaar(),
        claims: Map<String, Any> = emptyMap(),
        kid: String = KID,
        uitgever: PrivateKey = appManager().private,
        private val nonceSleutel: PrivateKey = paar.private,
    ) {
        val aes: ByteArray = TestApp.aesSleutel()
        val credential: String = TestApp.es256(credentialClaims(paar, nu) + claims, uitgever, kid)

        /** Het sessieverzoek voor een nieuwe challenge van magazijn [oin], met de nonce ondertekend voor [aud]. */
        fun sessieVerzoek(oin: String, aud: String = oin, certificaatSoort: String = "app_manager_jwt_certificate"): Pair<String, Map<String, Any>> {
            val challenge = payload(given().post("${basis(oin)}/rechtstreeks/challenges").then().statusCode(201).extract().asString())

            return voor(challenge, aud, certificaatSoort) to challenge
        }

        /**
         * Het sessieverzoek op een gegeven challenge. [jweHeader] gaat naar [TestApp.jweEcdh]: een
         * ander `alg`, `enc` of `typ` voor de gevallen die zonder sleutel al af te wijzen zijn.
         */
        fun voor(
            challenge: Map<String, Any>,
            aud: String,
            certificaatSoort: String = "app_manager_jwt_certificate",
            jweHeader: JweHeader = JweHeader(),
        ): String {
            val nonce = challenge["nonce"] as String

            val presentatie = mapOf(
                "app_nonce_signature" to TestApp.es256(mapOf("sub" to "challenge_response", "aud" to aud, "nonce" to nonce), nonceSleutel),
                "certificate_type" to certificaatSoort,
                "certificate" to credential,
                "session_aes_key" to Base64.getEncoder().encodeToString(aes),
            )
            val versleuteld = TestApp.jweEcdh(
                JsonUtil.toJson(presentatie),
                TestApp.publicKeyUitDer(challenge["ephemeral_organization_ec_public_key"] as String),
                alg = jweHeader.alg,
                enc = jweHeader.enc,
                typ = jweHeader.typ,
            )

            return JsonUtil.toJson(mapOf("nonce" to nonce, "encrypted_challenge_result" to versleuteld))
        }

        /** Een magazijnsessie bij [oin]; het `sessieToken`. */
        fun sessie(oin: String): String {
            val antwoord = given().contentType(ContentType.JSON).body(sessieVerzoek(oin).first).post("${basis(oin)}/rechtstreeks/sessies")
                .then().statusCode(201).extract().asString()

            return JsonUtil.parseJson(TestApp.ontsleutelDir(antwoord, aes))["sessieToken"] as String
        }
    }

    /** De header van de JWE met de presentatie; de defaults zijn wat het contract eist. */
    data class JweHeader(val alg: String = "ECDH-ES", val enc: String = "A256GCM", val typ: String? = "JWE")

    @Test
    fun `een challenge is een JWS met nonce en tijdelijke sleutel, ondertekend onder de kid van het magazijn`() {
        val jws = given().post("${basis(MAGAZIJN_A)}/rechtstreeks/challenges").then().statusCode(201)
            .header("Location", nullValue())
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
        val (body, _) = app.sessieVerzoek(MAGAZIJN_A)
        val nu = klok.instant()

        val antwoord = given().contentType(ContentType.JSON).body(body).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(201).header("Location", nullValue()).contentType("application/jose").extract().asString()
        val sessie = JsonUtil.parseJson(TestApp.ontsleutelDir(antwoord, app.aes))
        val geldigTot = Instant.parse(sessie["geldigTot"] as String)

        // De validator slaat application/jose over; hier staat wat het contract van de payload eist.
        assertTrue(COMPACT_JWE.matches(antwoord))
        assertEquals("JWE", TestApp.header(antwoord)["typ"])
        assertEquals(setOf("sessieToken", "geldigTot"), sessie.keys)
        assertTrue(Regex("^[A-Za-z0-9]{32}$").matches(sessie["sessieToken"] as String))
        // Het credential loopt een uur, dus de sessie haar volle 15 minuten, afgerond op een hele seconde.
        assertEquals(nu.plus(Duration.ofMinutes(15)).truncatedTo(ChronoUnit.SECONDS), geldigTot)
    }

    @Test
    fun `een afgewezen presentatie geeft 401 met WWW-Authenticate en CORS, en daarna is de nonce verbruikt`() {
        val (body, challenge) = App(klok.instant(), kid = "onbekend").sessieVerzoek(MAGAZIJN_A)

        // Zonder token in het verzoek hoort er geen foutcode in de uitdaging (RFC 6750, 3.1).
        given().contentType(ContentType.JSON).body(body).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(401)
            .contentType("application/problem+json")
            .header("WWW-Authenticate", "Bearer realm=\"$MAGAZIJN_A\"")
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Expose-Headers", containsString("WWW-Authenticate"))

        // Dezelfde nonce, nu met een presentatie die op zich klopt: de nonce is al verbruikt.
        given().contentType(ContentType.JSON).body(App(klok.instant()).voor(challenge, MAGAZIJN_A)).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(401)
    }

    @Test
    fun `een JWE-header die zonder sleutel al niet klopt, geeft 400 met CORS, en de nonce blijft bruikbaar`() {
        val app = App(klok.instant())
        val challenge = payload(given().post("${basis(MAGAZIJN_A)}/rechtstreeks/challenges").then().statusCode(201).extract().asString())

        listOf(JweHeader(alg = "ECDH-ES+A256KW"), JweHeader(enc = "A128GCM"), JweHeader(typ = null), JweHeader(typ = "JWT")).forEach { header ->
            valideer(MAGAZIJN_A).contentType(ContentType.JSON).body(app.voor(challenge, MAGAZIJN_A, jweHeader = header))
                .post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
                .then().statusCode(400)
                .contentType("application/problem+json")
                .header("Access-Control-Allow-Origin", "*")
                .header("WWW-Authenticate", nullValue())
        }

        // Dezelfde challenge, nu met een JWE volgens het contract: de nonce is nog niet gebruikt.
        given().contentType(ContentType.JSON).body(app.voor(challenge, MAGAZIJN_A)).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(201)
    }

    @Test
    fun `een presentatie die niet voor dit magazijn is, of geen credential draagt, wordt afgewezen`() {
        val app = App(klok.instant())

        // De nonce van A is bij B onbekend: elk magazijn heeft zijn eigen nonces.
        given().contentType(ContentType.JSON).body(app.sessieVerzoek(MAGAZIJN_A).first).post("${basis(MAGAZIJN_B)}/rechtstreeks/sessies")
            .then().statusCode(401)

        // Een nonce-handtekening voor B, aangeboden bij A: een magazijn dat de presentatie doorspeelt.
        given().contentType(ContentType.JSON).body(app.sessieVerzoek(MAGAZIJN_A, aud = MAGAZIJN_B).first).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(401)

        // Self-signed hoort bij de App Manager, niet bij een magazijn.
        given().contentType(ContentType.JSON).body(app.sessieVerzoek(MAGAZIJN_A, certificaatSoort = "certificate_type_self_signed").first)
            .post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(401)

        // Alleen exp is fout: iat en nbf liggen ervoor, de looptijd is twee uur.
        val nu = klok.instant()
        val verlopen = App(nu.minus(Duration.ofHours(2)), claims = mapOf("exp" to nu.minusSeconds(10).epochSecond))

        given().contentType(ContentType.JSON).body(verlopen.sessieVerzoek(MAGAZIJN_A).first).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(401)

        // Een bekende kid, maar ondertekend met een andere sleutel dan die van de App Manager.
        val vervalst = App(nu, uitgever = TestApp.sleutelpaar().private)

        given().contentType(ContentType.JSON).body(vervalst.sessieVerzoek(MAGAZIJN_A).first).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(401)

        // De juiste sleutel en kid, maar een uitgever die niet vertrouwd is: de sleutel hoort bij iss, niet alleen bij kid.
        val andereUitgever = App(nu, claims = mapOf("iss" to "00000000000000001200"))

        given().contentType(ContentType.JSON).body(andereUitgever.sessieVerzoek(MAGAZIJN_A).first).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(401)

        // De nonce ondertekend met een andere sleutel dan die in cnf.jwk: een gestolen credential.
        val gestolen = App(nu, nonceSleutel = TestApp.sleutelpaar().private)

        given().contentType(ContentType.JSON).body(gestolen.sessieVerzoek(MAGAZIJN_A).first).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(401)
    }

    @Test
    fun `een afgewezen presentatie noemt geen ongeldig token, ook niet als de app er een meestuurt`() {
        val (body, _) = App(klok.instant(), kid = "onbekend").sessieVerzoek(MAGAZIJN_A)

        given().contentType(ContentType.JSON).header("Authorization", "Bearer oud-token").body(body).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(401).header("WWW-Authenticate", "Bearer realm=\"$MAGAZIJN_A\"")
    }

    @Test
    fun `een volle sessie-opslag geeft 503, en daarna is de nonce verbruikt`() {
        // Eén app-sleutel bij C, tot het quotum per houder vol is. Met de vaste klok verlopen die
        // sessies in deze testrun niet; alleen deze app gebruikt die sleutel.
        val app = App(klok.instant())
        val statussen = (1..MAX_SESSIES_PER_HOUDER).map {
            given().contentType(ContentType.JSON).body(app.sessieVerzoek(MAGAZIJN_C).first).post("${basis(MAGAZIJN_C)}/rechtstreeks/sessies")
                .then().extract().statusCode()
        }

        assertEquals(List(MAX_SESSIES_PER_HOUDER) { 201 }, statussen)

        val (body, _) = app.sessieVerzoek(MAGAZIJN_C)

        valideer(MAGAZIJN_C).contentType(ContentType.JSON).body(body).post("${basis(MAGAZIJN_C)}/rechtstreeks/sessies")
            .then().statusCode(503).header("Retry-After", "900").header("Access-Control-Allow-Origin", "*")

        // Wat de spec bij ServiceUnavailable zegt: de nonce kan dan al verbruikt zijn.
        given().contentType(ContentType.JSON).body(body).post("${basis(MAGAZIJN_C)}/rechtstreeks/sessies")
            .then().statusCode(401)
    }

    @Test
    fun `een herhaalde Authorization-header faalt dicht, ook als een van beide geldig is`() {
        val token = App(klok.instant()).sessie(MAGAZIJN_A)

        listOf(listOf("Bearer $token", "Bearer onbekend"), listOf("Bearer onbekend", "Bearer $token"), listOf("Bearer $token", "Bearer $token")).forEach { waarden ->
            waarden.fold(given()) { verzoek, waarde -> verzoek.header("Authorization", waarde) }
                .get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten")
                .then().statusCode(401).header("WWW-Authenticate", "Bearer realm=\"$MAGAZIJN_A\"")
        }
    }

    @Test
    fun `een geslaagd antwoord is application-jose, ook als de app alleen problem-json accepteert`() {
        given().accept("application/problem+json").post("${basis(MAGAZIJN_A)}/rechtstreeks/challenges")
            .then().statusCode(201).contentType("application/jose")
    }

    @Test
    fun `een onvolledig sessieverzoek geeft 400 en geen 500, met CORS`() {
        listOf("{}", """{"nonce":"x"}""", "[]", "{kapot").forEach { body ->
            given().contentType(ContentType.JSON).body(body).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
                .then().statusCode(400).contentType("application/problem+json").header("Access-Control-Allow-Origin", "*")
        }
    }

    @Test
    fun `een onbekend magazijn geeft 404, ook met CORS, zodat de app de fout kan lezen`() {
        given().post("${basis("00000009000000009999")}/rechtstreeks/challenges")
            .then().statusCode(404).header("Access-Control-Allow-Origin", "*")
    }

    @Test
    fun `een magazijn in storing antwoordt op de tweede ingang volgens het contract`() {
        try {
            listOf(Gedrag(GedragModus.STUK), Gedrag(GedragModus.WEIGERT, foutStatus = 403)).forEach { gedrag ->
                magazijnen.stelGedragBij(MAGAZIJN_C, gedrag)

                valideer(MAGAZIJN_C).post("${basis(MAGAZIJN_C)}/rechtstreeks/challenges")
                    .then().statusCode(503).header("Retry-After", "30").header("Access-Control-Allow-Origin", "*")
                valideer(MAGAZIJN_C).contentType(ContentType.JSON).body("{}").post("${basis(MAGAZIJN_C)}/rechtstreeks/sessies")
                    .then().statusCode(503).header("Retry-After", "30")
                valideer(MAGAZIJN_C).get("${basis(MAGAZIJN_C)}/rechtstreeks/berichten")
                    .then().statusCode(503).header("Retry-After", "30")
            }

            magazijnen.stelGedragBij(MAGAZIJN_C, Gedrag(GedragModus.MALFORMED))

            // Een geslaagde POST, dus 201 zoals elke andere: alleen de inhoud is onbruikbaar.
            val onbruikbaar = given().post("${basis(MAGAZIJN_C)}/rechtstreeks/challenges").then().statusCode(201)
                .contentType("application/jose").extract().asString()

            assertFalse(COMPACT_JWS.matches(onbruikbaar))

            val geenSessie = given().contentType(ContentType.JSON).body("{}").post("${basis(MAGAZIJN_C)}/rechtstreeks/sessies")
                .then().statusCode(201).contentType("application/jose").extract().asString()

            assertFalse(COMPACT_JWE.matches(geenSessie))
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
            given().post("${basis(MAGAZIJN_B)}/rechtstreeks/challenges").then().extract().statusCode()
        }

        assertTrue(antwoorden.all { it == 201 }, antwoorden.toString())

        val vol = valideer(MAGAZIJN_B).post("${basis(MAGAZIJN_B)}/rechtstreeks/challenges").then()
            .statusCode(503).header("Access-Control-Allow-Origin", "*").extract()

        // Met de vaste klok verloopt de oudste nonce precies na zijn levensduur.
        assertEquals("60", vol.header("Retry-After"))
    }

    @Test
    fun `de preflight op de tweede ingang staat Authorization toe, zonder token`() {
        given().header("Origin", "https://app.test")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,content-type")
            .options("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(204)
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Allow-Headers", "Authorization, Content-Type")
            .header("Access-Control-Allow-Methods", "GET, POST")
            .header("Access-Control-Max-Age", "600")
            .header("API-Version", containsString("."))
            .header("Access-Control-Allow-Credentials", nullValue())

        given().header("Origin", "https://app.test")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "authorization")
            .options("${basis(MAGAZIJN_A)}/rechtstreeks/berichten")
            .then().statusCode(204).header("Access-Control-Allow-Methods", "GET, POST")

        // Een pad dat er alleen op lijkt, valt erbuiten.
        given().header("Origin", "https://app.test")
            .header("Access-Control-Request-Method", "POST")
            .options("${basis(MAGAZIJN_A)}/rechtstreeksx")
            .then().header("Access-Control-Allow-Origin", nullValue())
    }

    @Test
    fun `de eerste ingang blijft zonder CORS`() {
        given().header("Origin", "https://app.test").header("X-Ontvanger", ONTVANGER).get("${basis(MAGAZIJN_A)}/berichten")
            .then().statusCode(200).header("Access-Control-Allow-Origin", nullValue())
        given().header("Origin", "https://app.test")
            .header("Access-Control-Request-Method", "GET")
            .options("${basis(MAGAZIJN_A)}/berichten")
            .then().header("Access-Control-Allow-Origin", nullValue())
    }

    @Test
    fun `challenges, sessies en hun foutantwoorden voldoen aan het contract`() {
        val app = App(klok.instant())
        val (body, _) = app.sessieVerzoek(MAGAZIJN_A)

        valideer(MAGAZIJN_A).post("${basis(MAGAZIJN_A)}/rechtstreeks/challenges").then().statusCode(201)
        valideer(MAGAZIJN_A).contentType(ContentType.JSON).body(body).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies").then().statusCode(201)
        valideer(MAGAZIJN_A).contentType(ContentType.JSON).body(body).post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies").then().statusCode(401)
        valideer(MAGAZIJN_A).contentType(ContentType.JSON).body("{}").post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies").then().statusCode(400)
    }

    @Test
    fun `de foutantwoorden van het framework op de tweede ingang voldoen aan het contract`() {
        val token = App(klok.instant()).sessie(MAGAZIJN_A)
        val berichten = "${basis(MAGAZIJN_A)}/rechtstreeks/berichten"

        valideer(MAGAZIJN_A).accept(ContentType.JSON).post("${basis(MAGAZIJN_A)}/rechtstreeks/challenges")
            .then().statusCode(406).header("Access-Control-Allow-Origin", "*")
        valideer(MAGAZIJN_A).accept(ContentType.JSON).contentType(ContentType.JSON).body("{}").post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(406)
        valideer(MAGAZIJN_A).contentType(ContentType.TEXT).body("x").post("${basis(MAGAZIJN_A)}/rechtstreeks/sessies")
            .then().statusCode(415).header("Access-Control-Allow-Origin", "*")
        valideer(MAGAZIJN_A).accept(ContentType.JSON).header("Authorization", "Bearer $token").get(berichten).then().statusCode(406)
        // Validatie gaat vóór de sessie: ook zonder token een 400.
        valideer(MAGAZIJN_A).queryParam("pageSize", 0).get(berichten).then().statusCode(400)
        valideer(MAGAZIJN_A).header("Authorization", "Bearer $token").queryParam("pageSize", 0).get(berichten).then().statusCode(400)
        valideer(MAGAZIJN_A).header("Authorization", "Bearer $token").get("$berichten/geen-uuid").then().statusCode(404)
        valideer(MAGAZIJN_A).header("Authorization", "Bearer onbekend").get("$berichten/$BERICHT_ID").then().statusCode(401)
    }

    companion object {
        const val MAGAZIJN_A = "00000009000000000001"
        const val MAGAZIJN_B = "00000009000000000002"
        const val MAGAZIJN_C = "00000009000000000003"
        const val ONTVANGER = "KVK:90000014"
        const val BERICHT_ID = "11111111-2222-3333-4444-555555555555"
        val COMPACT_JWS = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
        val COMPACT_JWE = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
        const val APP_MANAGER_OIN = "00000000000000001100"
        const val KID = "test-1"
        const val MAX_CHALLENGES_PER_CLIENT = 20
        const val MAX_SESSIES_PER_HOUDER = 20

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

        /** Toetst alleen de antwoorden: de foutgevallen sturen bewust een verzoek dat niet aan de spec voldoet. */
        fun valideer(oin: String): RequestSpecification = given().filter(
            OpenApiValidationFilter(
                OpenApiInteractionValidator.createForSpecificationUrl("openapi/rechtstreeks-ophalen-api.yaml")
                    .withBasePathOverride(basis(oin))
                    .withLevelResolver(
                        LevelResolver.create()
                            .withLevel("validation.request", ValidationReport.Level.IGNORE)
                            // Zonder deze twee slaagt een test op een pad dat niet in de spec staat stil, zonder iets te toetsen.
                            .withLevel("validation.request.path.missing", ValidationReport.Level.ERROR)
                            .withLevel("validation.request.operation.notAllowed", ValidationReport.Level.ERROR)
                            .build(),
                    )
                    .build(),
            ),
        )

        fun credentialClaims(paar: KeyPair, nu: Instant): Map<String, Any> =
            mapOf(
                "cnf" to mapOf("jwk" to TestApp.jwk(paar.public as ECPublicKey)),
                "scope" to MagazijnVerifiers.SCOPE,
                "iss" to APP_MANAGER_OIN,
                "iat" to nu.epochSecond,
                "nbf" to nu.epochSecond,
                "exp" to nu.plus(Duration.ofHours(1)).epochSecond,
                "ontvanger" to ONTVANGER,
            )

        fun payload(jws: String): Map<String, Any> = JsonUtil.parseJson(String(Base64.getUrlDecoder().decode(jws.split('.')[1])))
    }
}
