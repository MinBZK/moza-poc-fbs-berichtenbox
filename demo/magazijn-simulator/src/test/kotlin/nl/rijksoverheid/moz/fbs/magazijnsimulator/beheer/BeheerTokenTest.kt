package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.runtime.StartupEvent
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.Optional

/**
 * De afscherming van het beheerpad.
 *
 * De WireMock-admin-API van de stubs op de gedeelde omgeving stond publiek en zonder authenticatie
 * open. Dat is precies het pad waarlangs iemand hier de demo zou kunnen legen of een magazijn kapot
 * zetten, dus die fout is de moeite van het niet-herhalen waard — en van het vastpinnen.
 */
@QuarkusTest
@TestProfile(BeheerTokenTest.MetToken::class)
class BeheerTokenTest {

    class MetToken : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("magazijnsimulator.beheer.token" to TOKEN)
    }

    @Test
    fun `zonder token komt er niets door`() {
        given()
            .`when`().get("/beheer/magazijnen")
            .then()
            .statusCode(401)
            .contentType(PROBLEM_JSON)
    }

    @Test
    fun `met een verkeerd token komt er niets door`() {
        given()
            .header(HEADER, "iets anders")
            .`when`().get("/beheer/magazijnen")
            .then()
            .statusCode(401)
    }

    /** Even lang als het echte token, zodat ook de tijdconstante vergelijking geraakt wordt. */
    @Test
    fun `een token van dezelfde lengte maar andere inhoud komt er niet door`() {
        given()
            .header(HEADER, "X".repeat(TOKEN.length))
            .`when`().get("/beheer/magazijnen")
            .then()
            .statusCode(401)
    }

    /**
     * Wat de afscherming draagt is de normalisatie van het pad. Zonder die ene regel opent
     * `//beheer/legen` het beheerpad zonder token, en dat is precies het soort gat waarlangs de
     * WireMock-admin-API van de stubs open stond. Geen enkele andere test raakt hem.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "/beheer/magazijnen",
            "//beheer/magazijnen",
            "///beheer/magazijnen",
            "/beheer//magazijnen",
            "/beheer/magazijnen/",
            "/./beheer/magazijnen",
            "/iets/../beheer/magazijnen",
            "/%62eheer/magazijnen",
        ],
    )
    fun `geen enkele schrijfwijze van het pad komt langs de afscherming`(pad: String) {
        given()
            .urlEncodingEnabled(false)
            .`when`().get(pad)
            .then()
            .statusCode(401)
    }

    @Test
    fun `met het juiste token wel`() {
        given()
            .header(HEADER, TOKEN)
            .`when`().get("/beheer/magazijnen")
            .then()
            .statusCode(200)
    }

    /**
     * Élk beheerpad, en niet alleen het lezen. Het filter kiest op padprefix, dus vandaag is dit één
     * codepad — maar verhuist `seed` ooit naar een eigen resource met een ander prefix, dan valt
     * precies dat endpoint erbuiten zonder dat iets rood wordt. En `seed` en de bulk zijn de twee
     * zwaarste knoppen: honderd magazijnen volschrijven of tegelijk op storing zetten.
     */
    @ParameterizedTest
    @MethodSource("beheerAanroepen")
    fun `elk beheerpad is afgeschermd`(methode: String, pad: String, body: String?) {
        listOf(null, "", "verkeerd-token").forEach { aangeboden ->
            val verzoek = given().contentType(ContentType.JSON)

            aangeboden?.let { verzoek.header(HEADER, it) }
            body?.let { verzoek.body(it) }

            verzoek
                .`when`().request(methode, pad)
                .then()
                .statusCode(401)
                .contentType("application/problem+json")
        }
    }

    /** Een 401 die tóch schrijft, is precies wat een filter dat te laat draait zou opleveren. */
    @Test
    fun `een geweigerde seed schrijft niets`() {
        given()
            .contentType(ContentType.JSON)
            .body(SEED_BODY)
            .`when`().post("/beheer/seed")
            .then()
            .statusCode(401)

        given()
            .header("X-Ontvanger", "KVK:90000001")
            .`when`().get("/magazijn/00000009000000000001/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", org.hamcrest.Matchers.equalTo(0))
    }

    /** Het gewone verkeer heeft niets met dit token te maken en hoort er niet op te stuiten. */
    @Test
    fun `het magazijn zelf blijft zonder token bereikbaar`() {
        given()
            .header("X-Ontvanger", "KVK:90000001")
            .`when`().get("/magazijn/00000009000000000001/api/v1/berichten")
            .then()
            .statusCode(200)
    }

    /**
     * Buiten dev en test hoort een ontbrekend token de boot te blokkeren. Een waarschuwing zou
     * betekenen dat de simulator maanden met een open beheerpad doordraait zonder dat iemand het
     * merkt — tot iemand hem vindt.
     */
    @Test
    fun `zonder token weigert de simulator buiten dev en test te starten`() {
        val fout = assertThrows<IllegalStateException> {
            BeheerToken(Optional.empty(), "prod").bijOpstart(StartupEvent())
        }

        assertThrows<IllegalStateException> { BeheerToken(Optional.of("  "), "prod").bijOpstart(StartupEvent()) }
        assert(fout.message?.contains("verplicht") == true) { "verwacht een melding die zegt wat er moet, was: ${fout.message}" }
    }

    @Test
    fun `met token start hij buiten dev en test wel`() {
        assertDoesNotThrow { BeheerToken(Optional.of(TOKEN), "prod").bijOpstart(StartupEvent()) }
    }

    @Test
    fun `onder dev en test mag het beheerpad open staan`() {
        assertDoesNotThrow { BeheerToken(Optional.empty(), "dev").bijOpstart(StartupEvent()) }
        assertDoesNotThrow { BeheerToken(Optional.empty(), "test").bijOpstart(StartupEvent()) }
    }

    private companion object {
        const val SEED_BODY = """{"ontvangers": ["KVK:90000001"], "berichtenPerMagazijn": 1, "bijlageElke": 0}"""

        @JvmStatic
        fun beheerAanroepen(): List<Arguments> = listOf(
            Arguments.of("GET", "/beheer/magazijnen", null),
            Arguments.of("POST", "/beheer/legen", null),
            Arguments.of("POST", "/beheer/seed", SEED_BODY),
            Arguments.of("PUT", "/beheer/magazijnen/00000009000000000001/gedrag", """{"modus": "STUK"}"""),
            Arguments.of(
                "PUT",
                "/beheer/gedrag",
                """{"aanpassingen": [{"oin": "00000009000000000001", "modus": "STUK"}]}""",
            ),
        )

        const val HEADER = "X-Beheer-Token"
        const val TOKEN = "demo-token-voor-de-test"
        const val PROBLEM_JSON = "application/problem+json"
    }
}
