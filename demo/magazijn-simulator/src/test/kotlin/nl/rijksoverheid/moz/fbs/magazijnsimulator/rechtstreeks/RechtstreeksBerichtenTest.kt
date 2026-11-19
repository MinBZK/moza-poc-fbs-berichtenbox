package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.magazijnsimulator.Logvanger
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.App
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.COMPACT_JWE
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.MAGAZIJN_A
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.MAGAZIJN_B
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.basis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.valideer
import nl.rijksoverheid.moz.fbs.rechtstreeks.TestApp
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.jose4j.json.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.time.Clock

/**
 * Berichten lezen via de tweede ingang: de ontvanger komt uit de magazijnsessie, het antwoord is
 * hetzelfde model als op de eerste ingang, versleuteld en met links naar de paden van deze ingang.
 */
@QuarkusTest
@TestProfile(RechtstreeksTest.Profiel::class)
class RechtstreeksBerichtenTest : MagazijnTestBasis() {

    @Inject
    lateinit var klok: Clock

    /** Een app met een sessie bij magazijn A, voor de ontvanger uit zijn credential. */
    private inner class Sessie {
        val app = App(klok.instant())
        val token = app.sessie(MAGAZIJN_A)
        val bearer = "Bearer $token"

        fun lijst(query: String = ""): Map<String, Any> = lees(
            given().header("Authorization", bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten$query")
                .then().statusCode(200).contentType("application/jose").extract().asString(),
        )

        fun lees(jwe: String): Map<String, Any> = JsonUtil.parseJson(ontsleutel(jwe, app.aes))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3])
    fun `de lijst bevat precies de berichten van de ontvanger uit de sessie`(aantal: Int) {
        repeat(aantal) { leverAan(ONTVANGER, "Eigen bericht $it") }
        leverAan(ANDERE_ONTVANGER, "Bericht van een ander")

        val lijst = Sessie().lijst()
        val berichten = lijst["berichten"] as List<*>

        assertEquals(aantal, berichten.size)
        assertEquals(aantal.toLong(), lijst["totalElements"])
        assertTrue(berichten.none { (it as Map<*, *>)["onderwerp"] == "Bericht van een ander" })
    }

    @Test
    fun `de lijst is hetzelfde model als op de eerste ingang, met links naar deze ingang`() {
        repeat(3) { leverAan(ONTVANGER, "Bericht $it") }

        val versleuteld = Sessie().lijst("?page=1&pageSize=2")
        val eersteIngang = given().header("X-Ontvanger", ONTVANGER).get("${basis(MAGAZIJN_A)}/berichten?page=1&pageSize=2")
            .then().statusCode(200).extract().asString()
        val verwacht = JsonUtil.parseJson(eersteIngang.replace("/api/v1/berichten", "/api/v1/rechtstreeks/berichten"))

        assertEquals(verwacht, versleuteld)
        assertTrue(JsonUtil.toJson(versleuteld).contains("/magazijn/$MAGAZIJN_A/api/v1/rechtstreeks/berichten"))
    }

    @Test
    fun `een eigen bericht komt met inhoud, versleuteld, en met een self naar deze ingang`() {
        val berichtId = leverAan(ONTVANGER, "Met inhoud")
        val sessie = Sessie()

        val jwe = given().header("Authorization", sessie.bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$berichtId")
            .then().statusCode(200).contentType("application/jose").header("Cache-Control", "no-store").extract().asString()
        val bericht = sessie.lees(jwe)

        assertEquals(berichtId, bericht["berichtId"])
        assertEquals("Inhoud van een demo-bericht.", bericht["inhoud"])
        assertEquals(
            "/magazijn/$MAGAZIJN_A/api/v1/rechtstreeks/berichten/$berichtId",
            URI.create(((bericht["_links"] as Map<*, *>)["self"] as Map<*, *>)["href"] as String).path,
        )
    }

    @Test
    fun `een bericht met bijlage is hetzelfde model als op de eerste ingang, en de bijlagelink geeft nog 404`() {
        val berichtId = leverAan(ONTVANGER, "Met bijlage", bijlage = true)
        val sessie = Sessie()

        val versleuteld = sessie.lees(
            given().header("Authorization", sessie.bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$berichtId")
                .then().statusCode(200).extract().asString(),
        )
        val eersteIngang = given().header("X-Ontvanger", ONTVANGER).get("${basis(MAGAZIJN_A)}/berichten/$berichtId")
            .then().statusCode(200).extract().asString()

        assertEquals(JsonUtil.parseJson(eersteIngang.replace("/api/v1/berichten", "/api/v1/rechtstreeks/berichten")), versleuteld)

        // TODO(#1215): het bijlagepad van de tweede ingang; tot dan geeft de link 404, zoals de spec zegt.
        val bijlage = ((versleuteld["bijlagen"] as List<*>).single() as Map<*, *>)
        val link = URI.create((((bijlage["_links"] as Map<*, *>)["self"] as Map<*, *>)["href"] as String)).path

        assertTrue(link.startsWith("/magazijn/$MAGAZIJN_A/api/v1/rechtstreeks/berichten/$berichtId/bijlagen/"), link)
        given().header("Authorization", sessie.bearer).get(link)
            .then().statusCode(404).header("Access-Control-Allow-Origin", "*")
            .contentType("application/problem+json").body("type", equalTo("urn:fbs:fout:niet-gevonden"))
    }

    @Test
    fun `een bericht voor dezelfde ontvanger bij een ander magazijn is via deze sessie niet te zien`() {
        val bijB = leverAan(ONTVANGER, "Bij magazijn B", magazijn = MAGAZIJN_B)
        val sessie = Sessie()

        assertEquals(0, (sessie.lijst()["berichten"] as List<*>).size)
        given().header("Authorization", sessie.bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$bijB").then().statusCode(404)
    }

    @Test
    fun `een bericht van een ander, een verwijderd of een onbekend bericht geeft 404`() {
        val vanEenAnder = leverAan(ANDERE_ONTVANGER, "Niet voor jou")
        val verwijderd = leverAan(ONTVANGER, "Weggegooid")

        // Een eigen actief bericht ernaast: een query die het id negeert, zou dat teruggeven.
        leverAan(ONTVANGER, "Eigen, actief")
        given().header("X-Ontvanger", ONTVANGER).delete("${basis(MAGAZIJN_A)}/berichten/$verwijderd").then().statusCode(204)

        val sessie = Sessie()

        val antwoorden = listOf(vanEenAnder, verwijderd, "11111111-2222-3333-4444-555555555555").map { id ->
            val antwoord = valideer(MAGAZIJN_A).header("Authorization", sessie.bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$id")
                .then().statusCode(404)
                .contentType("application/problem+json")
                .body("type", containsString("bericht-onbekend"))
                .header("Access-Control-Allow-Origin", "*")
                .extract()

            // De body op het id en het correlatie-id na, en de namen van de headers op Date na: daarin mag niets verschillen.
            val body = JsonUtil.parseJson(antwoord.asString())
                .filterKeys { it != "instance" }
                .mapValues { (naam, waarde) -> if (naam == "detail") (waarde as String).replace(id, "<id>") else waarde }
            val headers = antwoord.headers().asList().map { it.name }.filterNot { it.equals("Date", ignoreCase = true) }.toSet()

            body to headers
        }

        assertEquals(1, antwoorden.toSet().size, antwoorden.toString())
    }

    @Test
    fun `een meegestuurde X-Ontvanger verandert niets aan wiens berichten de sessie ziet`() {
        val vanEenAnder = leverAan(ANDERE_ONTVANGER, "Niet voor jou")
        val sessie = Sessie()

        val lijst = sessie.lees(
            given().header("Authorization", sessie.bearer).header("X-Ontvanger", ANDERE_ONTVANGER)
                .get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten").then().statusCode(200).extract().asString(),
        )

        assertEquals(0, (lijst["berichten"] as List<*>).size)
        given().header("Authorization", sessie.bearer).header("X-Ontvanger", ANDERE_ONTVANGER)
            .get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$vanEenAnder").then().statusCode(404)
    }

    /**
     * Zonder access-log koppelt alleen de logregel een 404 aan de 401 van dezelfde app bij hetzelfde
     * magazijn. Op beide ingangen noemt de 404-regel daarom het magazijn en de foutcode.
     */
    @Test
    fun `de 404-regel noemt op beide ingangen het magazijn en de foutcode`() {
        val onbekend = "11111111-2222-3333-4444-555555555555"
        val sessie = Sessie()

        val tweedeIngang = metLog {
            given().header("Authorization", sessie.bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$onbekend")
                .then().statusCode(404)
        }
        val eersteIngang = metLog {
            given().header("X-Ontvanger", ONTVANGER).get("${basis(MAGAZIJN_A)}/berichten/$onbekend").then().statusCode(404)
        }
        val afgewezen = metLog {
            given().header("Authorization", "Bearer onbekend").get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$onbekend")
                .then().statusCode(401)
        }

        listOf(tweedeIngang, eersteIngang).forEach { log ->
            assertTrue(log.contains("Clientfout 404"), log)
            assertTrue(log.contains("foutcode=bericht-onbekend"), log)
            assertTrue(log.contains("magazijn=$MAGAZIJN_A"), log)
            assertFalse(log.contains(ONTVANGER.substringAfter(':')), "de ontvanger hoort niet in de log: $log")
        }

        assertTrue(afgewezen.contains(MAGAZIJN_A), afgewezen)
    }

    private fun metLog(verzoek: () -> Unit): String = Logvanger().use { logvanger ->
        verzoek()
        logvanger.tekst()
    }

    @Test
    fun `zonder geldige sessie, of met een sessie van een ander magazijn, geeft elk berichtpad 401`() {
        val berichtId = leverAan(ONTVANGER, "Afgeschermd")
        val sessie = Sessie()

        listOf("berichten", "berichten/$berichtId").forEach { pad ->
            valideer(MAGAZIJN_A).get("${basis(MAGAZIJN_A)}/rechtstreeks/$pad")
                .then().statusCode(401).header("WWW-Authenticate", ZONDER_TOKEN)
            valideer(MAGAZIJN_A).header("Authorization", "Bearer onbekend").get("${basis(MAGAZIJN_A)}/rechtstreeks/$pad")
                .then().statusCode(401).header("WWW-Authenticate", ONGELDIG_TOKEN)
            given().header("Authorization", sessie.bearer).get("${basis(MAGAZIJN_B)}/rechtstreeks/$pad")
                .then().statusCode(401).header("WWW-Authenticate", "Bearer realm=\"$MAGAZIJN_B\", error=\"invalid_token\"")
        }
    }

    @Test
    fun `alleen een Bearer-token geeft toegang, met het schema in hoofd- of kleine letters`() {
        val berichtId = leverAan(ONTVANGER, "Alleen met Bearer")
        val sessie = Sessie()

        // Een geldig token zonder schema, of onder een ander schema, is geen Bearer-token: 401 zonder foutcode.
        listOf(sessie.token, "Basic ${sessie.token}", "Bearer").forEach { waarde ->
            valideer(MAGAZIJN_A).header("Authorization", waarde).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$berichtId")
                .then().statusCode(401).header("WWW-Authenticate", ZONDER_TOKEN)
        }

        // Het schema is hoofdletterongevoelig (RFC 9110, 11.1).
        listOf("Bearer", "bearer", "BEARER").forEach { schema ->
            val bericht = sessie.lees(
                given().header("Authorization", "$schema ${sessie.token}").get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$berichtId")
                    .then().statusCode(200).extract().asString(),
            )

            assertEquals(berichtId, bericht["berichtId"])
        }
    }

    @Test
    fun `bij meerdere eigen berichten geeft elk id zijn eigen bericht`() {
        val eerste = leverAan(ONTVANGER, "Eerste")
        val tweede = leverAan(ONTVANGER, "Tweede")
        val sessie = Sessie()

        listOf(eerste, tweede, eerste).forEach { id ->
            val bericht = sessie.lees(
                given().header("Authorization", sessie.bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$id")
                    .then().statusCode(200).extract().asString(),
            )

            assertEquals(id, bericht["berichtId"])
        }
    }

    @Test
    fun `hetzelfde nummer onder een ander type is van een ander`() {
        // 111222333 haalt de elfproef als BSN én als RSIN.
        val app = App(klok.instant(), claims = mapOf("ontvanger" to "RSIN:111222333"))
        val bearer = "Bearer ${app.sessie(MAGAZIJN_A)}"
        val vanRsin = leverAan("RSIN:111222333", "Voor de RSIN")
        val vanBsn = leverAan("BSN:111222333", "Zelfde cijfers, ander type")

        val lijst = JsonUtil.parseJson(
            ontsleutel(
                given().header("Authorization", bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten")
                    .then().statusCode(200).extract().asString(),
                app.aes,
            ),
        )
        val eigen = JsonUtil.parseJson(
            ontsleutel(
                given().header("Authorization", bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$vanRsin")
                    .then().statusCode(200).extract().asString(),
                app.aes,
            ),
        )

        assertEquals(listOf(vanRsin), (lijst["berichten"] as List<*>).map { (it as Map<*, *>)["berichtId"] })
        assertEquals(vanRsin, eigen["berichtId"])
        given().header("Authorization", bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$vanBsn").then().statusCode(404)
    }

    @Test
    fun `een paginering buiten de grenzen van het contract geeft 400`() {
        val sessie = Sessie()

        listOf("pageSize=0", "pageSize=101", "page=-1").forEach { query ->
            valideer(MAGAZIJN_A).header("Authorization", sessie.bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten?$query")
                .then().statusCode(400).contentType("application/problem+json")
        }
    }

    @Test
    fun `de antwoorden op de berichtpaden voldoen aan het contract`() {
        val berichtId = leverAan(ONTVANGER, "Contract")
        val sessie = Sessie()

        listOf("/berichten", "/berichten/$berichtId").forEach { pad ->
            val jwe = valideer(MAGAZIJN_A).header("Authorization", sessie.bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks$pad")
                .then().statusCode(200).extract().asString()

            // De validator slaat application/jose over; de payload hoort bij het schema waar `x-payload` naar wijst.
            valideerPayload(pad, ontsleutel(jwe, sessie.app.aes))
        }
    }

    @Test
    fun `x-payload van de berichtpaden wijst naar de schema's waartegen de payload getoetst wordt`() {
        val yaml = ObjectMapper(YAMLFactory())
        val tweedeIngang = javaClass.classLoader.getResourceAsStream("openapi/rechtstreeks-ophalen-api.yaml").use(yaml::readTree)
        val eersteIngang = javaClass.classLoader.getResourceAsStream("openapi/berichtenmagazijn-api.yaml").use(yaml::readTree)

        mapOf("/berichten" to "BerichtenLijst", "/berichten/{berichtId}" to "Bericht").forEach { (pad, schema) ->
            val xPayload = tweedeIngang.path("paths").path("/rechtstreeks$pad").path("get").path("responses").path("200")
                .path("content").path("application/jose").path("x-payload").path("\$ref").asText()
            val getoetst = eersteIngang.path("paths").path(pad).path("get").path("responses").path("200")
                .path("content").path("application/json").path("schema").path("\$ref").asText()

            assertEquals("./berichtenmagazijn-api.yaml#/components/schemas/$schema", xPayload)
            assertEquals("#/components/schemas/$schema", getoetst)
        }
    }

    /** De payload tegen het 200-schema van dezelfde operatie op de eerste ingang; zie de test op `x-payload`. */
    private fun valideerPayload(pad: String, payload: String) {
        val rapport = OpenApiInteractionValidator.createForSpecificationUrl("openapi/berichtenmagazijn-api.yaml")
            .withBasePathOverride(basis(MAGAZIJN_A))
            .withLevelResolver(
                LevelResolver.create()
                    // De headers horen bij het JWE-antwoord, niet bij de payload.
                    .withLevel("validation.response.header", ValidationReport.Level.IGNORE)
                    .build(),
            )
            .build()
            .validateResponse(
                "${basis(MAGAZIJN_A)}$pad",
                Request.Method.GET,
                SimpleResponse.Builder.ok().withContentType("application/json").withBody(payload).build(),
            )

        assertTrue(!rapport.hasErrors(), rapport.toString())
    }

    companion object {
        const val ONTVANGER = RechtstreeksTest.ONTVANGER
        const val ANDERE_ONTVANGER = "KVK:90000001"
        const val ZONDER_TOKEN = "Bearer realm=\"$MAGAZIJN_A\""
        const val ONGELDIG_TOKEN = "Bearer realm=\"$MAGAZIJN_A\", error=\"invalid_token\""

        /** Een antwoord van een berichtpad: compacte JWE met `typ` `JWE`, ontsleuteld met [aes]. */
        fun ontsleutel(jwe: String, aes: ByteArray): String {
            assertTrue(COMPACT_JWE.matches(jwe))
            assertEquals(mapOf("alg" to "dir", "enc" to "A256GCM", "typ" to "JWE"), TestApp.header(jwe))

            return TestApp.ontsleutelDir(jwe, aes)
        }

        fun leverAan(ontvanger: String, onderwerp: String, magazijn: String = MAGAZIJN_A, bijlage: Boolean = false): String {
            val (type, waarde) = ontvanger.split(':')
            val bijlagen = if (bijlage) """, "bijlagen": [{"naam": "bijlage.pdf", "mimeType": "application/pdf", "inhoud": "cGRm"}]""" else ""

            return given()
                .contentType(ContentType.JSON)
                .body(
                    """
                    {
                      "afzender": "$magazijn",
                      "ontvanger": {"type": "$type", "waarde": "$waarde"},
                      "onderwerp": "$onderwerp",
                      "inhoud": "Inhoud van een demo-bericht."$bijlagen
                    }
                    """.trimIndent(),
                )
                .post("${basis(magazijn)}/aanleveringen")
                .then().statusCode(201)
                .extract().path("berichtId")
        }
    }
}
