package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import nl.rijksoverheid.moz.fbs.common.exception.Foutcode
import nl.rijksoverheid.moz.fbs.kern.exception.FrameworkfoutDetail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Foutantwoorden die Quarkus zelf veroorzaakt: een pad dat niet bestaat, een methode die daar niet
 * mag, een mediatype dat niet te leveren of niet te lezen is.
 *
 * Quarkus geeft daarbij een melding mee die zijn eigen werking beschrijft ("Unable to find matching
 * target resource method"). Die hoort niet in `detail`. Dit toetst het tegen het echte framework,
 * zodat een nieuwe melding na een Quarkus-upgrade niet ongemerkt naar buiten komt. Ook de set
 * velden ligt vast: het schema staat extra velden toe, dus een contracttest merkt een veld te veel
 * niet op.
 */
@QuarkusTest
class FrameworkfoutenTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("gevallen")
    fun `een frameworkfout heeft de vaste tekst en precies de velden van een foutantwoord`(
        @Suppress("UNUSED_PARAMETER") naam: String,
        verzoek: () -> Response,
        status: Int,
        foutcode: Foutcode,
    ) {
        val antwoord = verzoek()

        assertEquals(status, antwoord.statusCode, antwoord.asString())
        assertEquals("application/problem+json", antwoord.contentType.substringBefore(';'))

        val velden = antwoord.jsonPath().getMap<String, Any?>("$")

        assertEquals(setOf("type", "title", "status", "detail", "instance"), velden.keys, antwoord.asString())
        assertEquals(foutcode.uri.toString(), velden["type"])
        assertEquals(FrameworkfoutDetail.voorStatus(status), velden["detail"])
        assertTrue((velden["instance"] as String).startsWith("urn:uuid:"), "instance: ${velden["instance"]}")
    }

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000001"
        const val BERICHT_ID = "11111111-2222-3333-4444-555555555555"

        private fun geval(naam: String, status: Int, foutcode: Foutcode, verzoek: () -> Response): Arguments =
            Arguments.of(naam, verzoek, status, foutcode)

        @JvmStatic
        fun gevallen(): Stream<Arguments> = Stream.of(
            geval("onbekend pad onder een bericht", 404, Foutcode.NIET_GEVONDEN) {
                given().header(ONTVANGER_HEADER, ONTVANGER).get("/api/v1/berichten/$BERICHT_ID/bijlagen/$BERICHT_ID/x")
            },
            geval("onbekend pad in de API", 404, Foutcode.NIET_GEVONDEN) {
                given().get("/api/v1/bestaat-niet")
            },
            geval("bericht-id dat geen UUID is", 404, Foutcode.NIET_GEVONDEN) {
                given().header(ONTVANGER_HEADER, ONTVANGER).get("/api/v1/berichten/geen-uuid")
            },
            geval("methode niet toegestaan", 405, Foutcode.ONGELDIG_VERZOEK) {
                given().header(ONTVANGER_HEADER, ONTVANGER).put("/api/v1/berichten")
            },
            geval("Accept niet te leveren", 406, Foutcode.ONGELDIG_VERZOEK) {
                given().header(ONTVANGER_HEADER, ONTVANGER).accept("application/xml").get("/api/v1/berichten")
            },
            geval("Content-Type niet ondersteund", 415, Foutcode.ONGELDIG_VERZOEK) {
                given().contentType(ContentType.TEXT).body("geen json").post("/api/v1/aanleveringen")
            },
        )
    }
}
