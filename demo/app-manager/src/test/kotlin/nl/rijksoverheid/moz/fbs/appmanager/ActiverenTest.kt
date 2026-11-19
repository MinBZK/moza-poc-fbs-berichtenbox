package nl.rijksoverheid.moz.fbs.appmanager

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import io.restassured.config.EncoderConfig
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService
import nl.rijksoverheid.moz.fbs.rechtstreeks.OpslagVolException
import nl.rijksoverheid.moz.fbs.rechtstreeks.TestApp
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.jose4j.json.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/** Het hele activeren zoals een app het doorloopt, met de app-kant uit de tests van de library. */
@QuarkusTest
class ActiverenTest {

    @Inject
    lateinit var sleutel: Ondertekensleutel

    @Inject
    lateinit var registraties: Registraties

    @Inject
    lateinit var personas: PersonaService

    companion object {

        const val EIGEN_ORIGIN = "https://app-manager.test"

        /** Een JWS of JWE is tekst; RestAssured kent `application/jose` niet uit zichzelf. */
        @JvmStatic
        @BeforeAll
        fun joseAlsTekst() {
            RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/jose", ContentType.TEXT),
            )
        }
    }

    /** Vangt wat een logger schrijft, zodat een test kan zien wat er wel en niet in de log komt. */
    class Logvanger(naam: String) : Handler(), AutoCloseable {
        private val logger = Logger.getLogger(naam)
        private val opmaak = SimpleFormatter()
        val regels = mutableListOf<LogRecord>()

        init {
            logger.addHandler(this)
        }

        override fun publish(record: LogRecord) {
            synchronized(regels) { regels += record }
        }

        override fun flush() = Unit

        override fun close() {
            logger.removeHandler(this)
        }

        fun tekst(): String = synchronized(regels) { regels.joinToString("\n") { opmaak.format(it) } }

        fun waarschuwingen(): List<String> = synchronized(regels) { regels.filter { it.level == Level.WARNING }.map(opmaak::format) }
    }

    /** Een keuze zoals de eigen keuzepagina hem verstuurt: met de cookie en de eigen `Origin`. */
    private fun keuze(koppeling: String?, origin: String? = EIGEN_ORIGIN): RequestSpecification = given().redirects().follow(false)
        .apply { if (koppeling != null) cookie(ActiverenResource.COOKIE, koppeling) }
        .apply { if (origin != null) header("Origin", origin) }

    /** Een app met een eigen sleutel en, na `complete`, een sessie bij de App Manager. */
    inner class App(val paar: KeyPair = TestApp.sleutelpaar()) {

        val aes: ByteArray = TestApp.aesSleutel()
        lateinit var sessieToken: String

        /** [eerstZonderTyp]: stuur de presentatie eerst zonder `typ` `JWE`; dat hoort `400` te geven en de nonce te laten staan. */
        fun maakSessie(eerstZonderTyp: Boolean = false): App {
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

            if (eerstZonderTyp) {
                given().contentType(ContentType.JSON)
                    .body(mapOf("nonce" to nonce, "encrypted_challenge_result" to TestApp.jweEcdh(JsonUtil.toJson(presentatie), tijdelijk, typ = null)))
                    .post("/v1/complete")
                    .then().statusCode(400).contentType("application/problem+json").body(containsString("urn:fbs:fout:ongeldig-verzoek"))
            }

            val antwoord = given().contentType(ContentType.JSON)
                .body(mapOf("nonce" to nonce, "encrypted_challenge_result" to TestApp.jweEcdh(JsonUtil.toJson(presentatie), tijdelijk)))
                .post("/v1/complete")
                .then().statusCode(200).extract().asString()

            sessieToken = JsonUtil.parseJson(TestApp.ontsleutelDir(antwoord, aes))["session_token"] as String

            return this
        }

        /** `/register_app` met het token: geeft de cookie met de koppeling. */
        fun koppel(): String = given().redirects().follow(false)
            .queryParam("session_token", sessieToken)
            .get("/register_app")
            .then().statusCode(303).header("Location", equalTo("$EIGEN_ORIGIN/register_app"))
            .extract().cookie(ActiverenResource.COOKIE)

        /** `/register_app` en de keuze; geeft het token dat de app uit de JWE in het fragment haalt. */
        fun kies(persona: String): String {
            val locatie = keuze(koppel()).formParam("persona", persona)
                .post("/register_app/kies")
                .then().statusCode(303).extract().header("Location")

            assertTrue(locatie.startsWith("https://app.test/#registration_token="), locatie)

            val jwe = URLDecoder.decode(locatie.substringAfter("#registration_token="), Charsets.UTF_8)
            val bericht = JsonUtil.parseJson(TestApp.ontsleutelDir(jwe, aes))

            assertEquals("registration_token", bericht["sub"])
            assertEquals("https://app-manager.test/", bericht["iss"])

            return bericht["registration_token"] as String
        }

        fun credential(registratie: String, iss: String = ActiverenResource.APP_ISS, sub: String = "registration_token"): Response = given()
            .header("Authorization", sessieToken)
            .contentType("application/jose")
            .body(TestApp.jweDir(JsonUtil.toJson(mapOf("sub" to sub, "iss" to iss, "registration_token" to registratie)), aes))
            .post("/credential")

        /** Het credential, ontsleuteld en met de handtekening van de App Manager gecontroleerd. */
        fun claims(registratie: String): Map<String, Any> {
            val antwoord = credential(registratie).then().statusCode(200).extract().asString()

            return TestApp.verifieerJws(TestApp.ontsleutelDir(antwoord, aes), sleutel.publiek)
        }
    }

    @Test
    fun `een app die een persona kiest, krijgt een credential voor die ondernemer op zijn eigen sleutel`() {
        val app = App().maakSessie()
        val registratie = app.kies("vandijk")
        val voor = Instant.now().epochSecond

        val antwoord = app.credential(registratie).then().statusCode(200).extract().asString()
        val na = Instant.now().epochSecond
        val vc = TestApp.ontsleutelDir(antwoord, app.aes)
        val claims = TestApp.verifieerJws(vc, sleutel.publiek)
        val iat = claims["iat"] as Long

        assertEquals(mapOf("alg" to "ES256", "typ" to "jwt", "kid" to "app-manager-1"), TestApp.header(vc))
        assertEquals(AppManagerConfig.DEMO_OIN, claims["iss"])
        assertEquals("KVK:90000014", claims["ontvanger"])
        assertEquals(AppManagerConfig.SCOPE, claims["scope"])
        assertTrue(iat in voor..na, "iat $iat ligt niet tussen $voor en $na")
        assertEquals(iat, claims["nbf"])
        assertEquals(iat + 3600, claims["exp"])
        assertEquals(mapOf("jwk" to TestApp.jwk(app.paar.public as ECPublicKey)), claims["cnf"])
    }

    @Test
    fun `elke persona levert een credential met haar eigen ontvanger`() {
        personas.alle().forEach { persona ->
            val app = App().maakSessie()
            val registratie = app.kies(persona.id)

            assertEquals(persona.ontvanger, app.claims(registratie)["ontvanger"], persona.id)

            // Elke keuze telt mee in het quotum van dit adres; de andere tests hebben die ruimte nodig.
            registraties.vergeet(registratie)
        }
    }

    @Test
    fun `met hetzelfde registration_token haalt dezelfde sleutel later een vers credential op`() {
        val eerste = App().maakSessie()
        val registratie = eerste.kies("pietersen")
        val vorige = eerste.claims(registratie)
        val later = App(eerste.paar).maakSessie()

        val vers = later.claims(registratie)

        assertEquals(vorige["cnf"], vers["cnf"])
        assertEquals(vorige["ontvanger"], vers["ontvanger"])
    }

    @Test
    fun `de registratie van een keuze leeft zo lang als de configuratie zegt`() {
        val voor = Instant.now()
        val registratie = App().maakSessie().kies("vandijk")
        val na = Instant.now()

        val verlooptOp = registraties.registratie(registratie)!!.verlooptOp

        assertTrue(verlooptOp in voor.plus(Duration.ofDays(1))..na.plus(Duration.ofDays(1)), "$verlooptOp")
    }

    @Test
    fun `een gestolen registration_token levert geen credential op een andere sleutel, en trekt de registratie niet in`() {
        val eigenaar = App().maakSessie()
        val registratie = eigenaar.kies("vandijk")
        val dief = App().maakSessie()

        Logvanger(ActiverenResource::class.java.name).use { log ->
            dief.credential(registratie).then().statusCode(401).header("WWW-Authenticate", "Activatiesessie")

            assertEquals(1, log.waarschuwingen().size, log.tekst())
            assertTrue(log.tekst().contains(TestApp.thumbprint(dief.paar.public as ECPublicKey)), log.tekst())
            assertFalse(log.tekst().contains(registratie), "het registration_token hoort niet in de log")
        }

        eigenaar.credential(registratie).then().statusCode(200)
    }

    @Test
    fun `een verzoek om een credential met een verkeerde iss of sub, onbekend token of onzin wordt afgewezen`() {
        val app = App().maakSessie()
        val registratie = app.kies("vandijk")

        app.credential(registratie, iss = "https://aanvaller.example/").then().statusCode(401)
        app.credential(registratie, sub = "iets anders").then().statusCode(401)
        app.credential("onbekend").then().statusCode(401)
        given().header("Authorization", app.sessieToken).contentType("application/jose")
            .body(TestApp.jweDir("geen json", app.aes)).post("/credential").then().statusCode(401)

        // Zonder sessie gaat de sessie voor, ook bij een body die zonder sleutel al niet klopt.
        given().contentType("application/jose").body("x").post("/credential").then().statusCode(401).contentType("application/problem+json")
    }

    @Test
    fun `een JWE die zonder sleutel al niet klopt, geeft 400 en laat de nonce staan`() {
        val app = App().maakSessie(eerstZonderTyp = true)

        // Binnen de sessie hetzelfde: een registration_token-verzoek zonder typ is ongeldig, niet afgewezen.
        given().header("Authorization", app.sessieToken).contentType("application/jose")
            .body(TestApp.jweDir("{}", app.aes, typ = null)).post("/credential")
            .then().statusCode(400).contentType("application/problem+json")
        given().header("Authorization", app.sessieToken).contentType("application/jose").body("x").post("/credential")
            .then().statusCode(400)
    }

    @Test
    fun `het session_token werkt één keer, en een tweede poging valt in de log op`() {
        val app = App().maakSessie()

        app.kies("vandijk")

        Logvanger(Foutvertaling::class.java.name).use { log ->
            given().redirects().follow(false).queryParam("session_token", app.sessieToken).get("/register_app")
                .then().statusCode(401).contentType(containsString("text/html"))

            assertTrue(log.waarschuwingen().any { it.contains("SESSIE_TOKEN_HERGEBRUIKT") }, log.tekst())
            assertFalse(log.tekst().contains(app.sessieToken), "het session_token hoort niet in de log")
        }
    }

    @Test
    fun `het omwisselen geeft een cookie die alleen deze host terugkrijgt, en een adres zonder token`() {
        val app = App().maakSessie()

        val antwoord = given().redirects().follow(false).queryParam("session_token", app.sessieToken).get("/register_app")
            .then().statusCode(303)
            .header("Location", equalTo("$EIGEN_ORIGIN/register_app"))
            .header("Location", not(containsString("session_token")))
            .extract()
        val cookie = antwoord.detailedCookie(ActiverenResource.COOKIE)

        assertTrue(ActiverenResource.COOKIE.startsWith("__Host-"))
        assertTrue(cookie.isHttpOnly)
        assertTrue(cookie.isSecured)
        assertEquals("/", cookie.path)
        assertEquals("Lax", cookie.sameSite)
        assertFalse(cookie.hasDomain())

        val wis = keuze(cookie.value).formParam("persona", "vandijk").post("/register_app/kies")
            .then().statusCode(303)
            .extract().detailedCookie(ActiverenResource.COOKIE)

        assertEquals(0, wis.maxAge)
    }

    @Test
    fun `de keuzepagina laadt niets van buiten en geeft geen Referer door`() {
        val koppeling = App().maakSessie().koppel()

        given().cookie(ActiverenResource.COOKIE, koppeling).get("/register_app")
            .then().statusCode(200)
            .header("Content-Security-Policy", "default-src 'none'; form-action 'self' https://app.test; frame-ancestors 'none'; base-uri 'none'")
            .header("Referrer-Policy", "no-referrer")
            .header("X-Frame-Options", "DENY")
            .header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "no-store")
            .body(containsString("Garage Van Dijk B.V."))
    }

    @Test
    fun `een koppeling levert één keuze op, ook bij een dubbele submit, en zonder koppeling is er geen keuzepagina`() {
        val koppeling = App().maakSessie().koppel()

        val eerste = keuze(koppeling).formParam("persona", "vandijk")
            .post("/register_app/kies").then().statusCode(303).extract().header("Location")

        // De browser toont het antwoord op het laatste verzoek; dat moet het token van de eerste keuze dragen.
        listOf("vandijk", "pietersen").forEach { persona ->
            keuze(koppeling).formParam("persona", persona).post("/register_app/kies").then().statusCode(303).header("Location", eerste)
        }

        given().cookie(ActiverenResource.COOKIE, koppeling).get("/register_app").then().statusCode(401)
        given().get("/register_app").then().statusCode(401).contentType(containsString("text/html")).header("WWW-Authenticate", "Activatiesessie")
        given().cookie(ActiverenResource.COOKIE, "onbekend").get("/register_app").then().statusCode(401)
        keuze(null).formParam("persona", "vandijk").post("/register_app/kies").then().statusCode(401).contentType(containsString("text/html"))
    }

    @Test
    fun `een keuze die niet van de eigen keuzepagina komt, wordt geweigerd en laat de koppeling staan`() {
        val koppeling = App().maakSessie().koppel()

        listOf(null, "https://app.test", "https://ander.app-manager.test", "http://app-manager.test").forEach { origin ->
            keuze(koppeling, origin).formParam("persona", "vandijk").post("/register_app/kies").then().statusCode(401)
        }

        keuze(koppeling).formParam("persona", "vandijk").post("/register_app/kies").then().statusCode(303)
    }

    @Test
    fun `een formulier met kapotte encoding geeft 400, geen 500`() {
        val koppeling = App().maakSessie().koppel()

        listOf("persona=%zz", "persona=%2", "", "persona").forEach { body ->
            keuze(koppeling).contentType(ContentType.URLENC).body(body).post("/register_app/kies").then().statusCode(400)
        }

        // Een kapot ander veld stoort niet, en de koppeling heeft al die pogingen overleefd.
        keuze(koppeling).contentType(ContentType.URLENC).body("persona=vandijk&x=%zz").post("/register_app/kies").then().statusCode(303)
    }

    @Test
    fun `een onbekende persona geeft een foutpagina en laat de koppeling staan`() {
        val koppeling = App().maakSessie().koppel()

        keuze(koppeling).formParam("persona", "bestaat-niet").post("/register_app/kies")
            .then().statusCode(400).contentType(containsString("text/html")).header("Content-Security-Policy", Keuzepagina.CSP_ZONDER_FORMULIER)
        keuze(koppeling).formParam("persona", "vandijk").post("/register_app/kies").then().statusCode(303)
    }

    @Test
    fun `een vol quotum geeft een foutpagina met Retry-After, en daarna werkt dezelfde koppeling nog`() {
        val koppeling = App().maakSessie().koppel()
        val vulling = mutableListOf<String>()

        try {
            while (true) vulling += registraties.registreer("KVK:90000014", "vulling-${vulling.size}", "127.0.0.1", Duration.ofMinutes(5))
        } catch (vol: OpslagVolException) {
            // Het quotum van dit adres is vol.
        }

        try {
            keuze(koppeling).formParam("persona", "vandijk").post("/register_app/kies")
                .then().statusCode(503).contentType(containsString("text/html")).header("Retry-After", "60")
        } finally {
            vulling.forEach(registraties::vergeet)
        }

        keuze(koppeling).formParam("persona", "vandijk").post("/register_app/kies").then().statusCode(303)
    }

    @Test
    fun `een onvolledige envelop of een onleesbare JWE is een ongeldig verzoek`() {
        given().contentType(ContentType.JSON).body(mapOf("nonce" to "x")).post("/v1/complete")
            .then().statusCode(400).contentType("application/problem+json")
        given().contentType(ContentType.JSON).body("{}").post("/v1/complete").then().statusCode(400)
        given().contentType(ContentType.JSON).body(mapOf("nonce" to "x", "encrypted_challenge_result" to "y")).post("/v1/complete")
            .then().statusCode(400)

        listOf("""{"nonce":{},"encrypted_challenge_result":"y"}""", """{"nonce":["x"]}""", "[]", "\"tekst\"", "{kapot").forEach { body ->
            given().contentType(ContentType.JSON).body(body).post("/v1/complete").then().statusCode(400).contentType("application/problem+json")
        }
    }

    @Test
    fun `een geldige JWE met een onbekende nonce wordt afgewezen`() {
        val tijdelijk = TestApp.sleutelpaar().public as ECPublicKey

        given().contentType(ContentType.JSON)
            .body(mapOf("nonce" to "b25iZWtlbmRlIG5vbmNl", "encrypted_challenge_result" to TestApp.jweEcdh("{}", tijdelijk)))
            .post("/v1/complete")
            .then().statusCode(401).contentType("application/problem+json")
    }

    @Test
    fun `een fout van het framework heeft dezelfde vorm als de eigen fouten`() {
        given().contentType(ContentType.JSON).body("{}").post("/credential").then().statusCode(415).contentType("application/problem+json")
        given().get("/v1/challenge").then().statusCode(405).contentType("application/problem+json")
    }

    @Test
    fun `een onverwachte fout geeft een correlatie-id, en de log noemt de query niet`() {
        Logvanger(Foutvertaling::class.java.name).use { log ->
            given().queryParam("session_token", "geheimtoken").get("/test/onverwacht")
                .then().statusCode(500).contentType("application/problem+json").body(containsString("urn:uuid:"))

            assertTrue(log.tekst().contains("/test/onverwacht"), log.tekst())
            assertFalse(log.tekst().contains("geheimtoken"), log.tekst())
        }
    }

    @Test
    fun `een app van een andere origin mag de protocolpaden aanroepen, met Authorization`() {
        given().header("Origin", "https://app.test")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,content-type")
            .options("/credential")
            .then().statusCode(200)
            .header("Access-Control-Allow-Origin", containsString("app.test"))
            .header("Access-Control-Allow-Methods", containsString("POST"))
            .header("Access-Control-Allow-Headers", containsString("Authorization"))
            .header("Access-Control-Allow-Credentials", not("true"))

        // Zonder deze header ziet de app bij een 401 of 503 niet wat er aan de hand is.
        given().header("Origin", "https://app.test").contentType("application/jose").body("x").post("/credential")
            .then().statusCode(401)
            .header("Access-Control-Expose-Headers", containsString("WWW-Authenticate"))
            .header("Access-Control-Expose-Headers", containsString("Retry-After"))
    }
}
