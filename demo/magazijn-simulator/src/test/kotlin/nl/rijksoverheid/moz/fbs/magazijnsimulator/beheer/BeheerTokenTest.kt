package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.runtime.StartupEvent
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test
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

    @Test
    fun `met het juiste token wel`() {
        given()
            .header(HEADER, TOKEN)
            .`when`().get("/beheer/magazijnen")
            .then()
            .statusCode(200)
    }

    /** Ook de handelingen die iets kapotmaken, niet alleen het lezen. */
    @Test
    fun `ook legen en gedrag bijstellen zijn afgeschermd`() {
        given().`when`().post("/beheer/legen").then().statusCode(401)

        given()
            .contentType(ContentType.JSON)
            .body("""{"modus": "STUK"}""")
            .`when`().put("/beheer/magazijnen/00000009000000000001/gedrag")
            .then()
            .statusCode(401)
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
        const val HEADER = "X-Beheer-Token"
        const val TOKEN = "demo-token-voor-de-test"
        const val PROBLEM_JSON = "application/problem+json"
    }
}
