package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.magazijnsimulator.Logvanger
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.herkenbareDelen
import nl.rijksoverheid.moz.fbs.magazijnsimulator.VasteKlokProducer
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksBerichtenTest.Companion.ONGELDIG_TOKEN
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksBerichtenTest.Companion.ONTVANGER
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksBerichtenTest.Companion.leverAan
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.App
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.MAGAZIJN_A
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.basis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.valideer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Een magazijnsessie die verloopt. Eigen profiel en dus een eigen applicatie: de opslag van
 * challenges en sessies is applicatiebreed, en de tests van de tweede ingang rekenen erop dat er
 * onder de vaste klok niets uit verloopt. Eén verzetting ruimt alles op.
 */
@QuarkusTest
@TestProfile(RechtstreeksVerloopTest.Profiel::class)
class RechtstreeksVerloopTest : MagazijnTestBasis() {

    class Profiel : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = RechtstreeksTest.Profiel().configOverrides
    }

    @Inject
    lateinit var klokProducer: VasteKlokProducer

    @AfterEach
    fun zetKlokTerug() {
        klokProducer.klok.verschuiving = Duration.ZERO
    }

    @Test
    fun `een verlopen sessie geeft op elk berichtpad 401`() {
        val berichtId = leverAan(ONTVANGER, "Verloopt")
        val bearer = "Bearer ${App(klokProducer.klok.instant()).sessie(MAGAZIJN_A)}"

        klokProducer.klok.verschuiving = SESSIEDUUR.minusSeconds(1)
        given().header("Authorization", bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$berichtId").then().statusCode(200)

        klokProducer.klok.verschuiving = SESSIEDUUR

        listOf("berichten", "berichten/$berichtId").forEach { pad ->
            valideer(MAGAZIJN_A).header("Authorization", bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/$pad")
                .then().statusCode(401).header("WWW-Authenticate", ONGELDIG_TOKEN)
        }
    }

    /**
     * De app mag niet kunnen afleiden of zijn token verliep of nooit bestond; de operator moet dat
     * juist wel kunnen. Voor de app dus exact hetzelfde antwoord, op het correlatie-id na, en in de
     * log een andere reden.
     */
    @Test
    fun `een verlopen en een onbekend token geven hetzelfde antwoord, met een andere reden in de log`() {
        val berichtId = leverAan(ONTVANGER, "Verloopt")
        val token = App(klokProducer.klok.instant()).sessie(MAGAZIJN_A)

        klokProducer.klok.verschuiving = SESSIEDUUR

        val (verlopen, logVerlopen) = afgewezen(token, berichtId)
        val (onbekend, logOnbekend) = afgewezen(VERZONNEN_TOKEN, berichtId)

        listOf(verlopen, onbekend).forEach { antwoord ->
            assertEquals(401, antwoord.statusCode())
            assertEquals(ONGELDIG_TOKEN, antwoord.header("WWW-Authenticate"))
        }

        assertEquals(zonderInstance(onbekend), zonderInstance(verlopen))
        assertEquals(onbekend.contentType(), verlopen.contentType())
        assertTrue(logVerlopen.contains("SESSIE_VERLOPEN"), logVerlopen)
        assertTrue(logOnbekend.contains("SESSIE_ONBEKEND"), logOnbekend)
        assertFalse(logVerlopen.contains("SESSIE_ONBEKEND"), logVerlopen)
        assertFalse(logOnbekend.contains("SESSIE_VERLOPEN"), logOnbekend)

        // Het token is een geheim, ook verlopen of verzonnen.
        listOf(token to logVerlopen, VERZONNEN_TOKEN to logOnbekend).forEach { (geheim, log) ->
            herkenbareDelen(geheim).forEach { deel -> assertFalse(log.contains(deel), "het token hoort niet in de log: $log") }
        }
    }

    private fun afgewezen(token: String, berichtId: String): Pair<ExtractableResponse<Response>, String> =
        Logvanger().use { logvanger ->
            val antwoord = given().header("Authorization", "Bearer $token")
                .get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$berichtId")
                .then().extract()

            antwoord to logvanger.tekst()
        }

    private fun zonderInstance(antwoord: ExtractableResponse<Response>): Map<String, Any?> =
        antwoord.jsonPath().getMap<String, Any?>("").minus("instance")

    private companion object {
        val SESSIEDUUR: Duration = Duration.ofMinutes(15)

        /** Dezelfde vorm als een uitgegeven token, zodat alleen de opslag het verschil kent. */
        val VERZONNEN_TOKEN = "Verzonnen".padEnd(32, '0')
    }
}
