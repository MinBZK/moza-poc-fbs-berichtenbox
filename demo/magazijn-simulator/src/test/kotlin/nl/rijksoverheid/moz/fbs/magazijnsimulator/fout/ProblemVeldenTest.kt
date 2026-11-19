package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.kern.exception.FrameworkfoutDetail
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.Gedrag
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.GedragModus
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.GesimuleerdeMagazijnen
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * De vorm van elk soort foutantwoord: welke velden er staan, en wat er in `detail` staat.
 *
 * Het schema `Problem` staat extra velden toe, dus de contracttests merken een veld te veel niet op.
 * Deze toets legt de set velden per soort fout vast. Bij een fout die het framework veroorzaakt,
 * hoort in `detail` een vaste Nederlandse tekst te staan, en niet de interne melding van het
 * framework.
 */
@QuarkusTest
class ProblemVeldenTest : MagazijnTestBasis() {

    @Inject
    lateinit var magazijnen: GesimuleerdeMagazijnen

    @AfterEach
    fun herstel() {
        magazijnen.herstelGedrag()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gevallen")
    fun `elk foutantwoord heeft precies de velden die bij zijn soort horen`(
        @Suppress("UNUSED_PARAMETER") naam: String,
        verzoek: () -> Response,
        status: Int,
        foutcode: Foutcode,
        detail: DetailVerwachting,
    ) {
        assertProblemVelden(verzoek(), status, foutcode, detail)
    }

    /** Een storing komt uit het gedrag-filter en niet uit een mapper; ook die mag geen extra veld dragen. */
    @Test
    fun `een gesimuleerde storing heeft geen extra veld`() {
        assertTrue(magazijnen.stelGedragBij(MAGAZIJN, Gedrag(GedragModus.STUK, latencyP50Ms = 0, latencyP95Ms = 0)))

        val antwoord = given().header(ONTVANGER_HEADER, ONTVANGER).get("$BASIS/berichten")

        assertProblemVelden(
            antwoord,
            antwoord.statusCode,
            Foutcode.voorStatus(antwoord.statusCode),
            DetailVerwachting.Bevat("ingesteld op stuk"),
        )
    }

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000001"
        const val MAGAZIJN = "00000009000000000003"
        const val BASIS = "/magazijn/$MAGAZIJN/api/v1"
        const val BERICHT_ID = "11111111-2222-3333-4444-555555555555"

        private fun vast(status: Int) = DetailVerwachting.Precies(FrameworkfoutDetail.voorStatus(status)!!)

        private fun geval(
            naam: String,
            status: Int,
            foutcode: Foutcode,
            detail: DetailVerwachting,
            verzoek: () -> Response,
        ): Arguments = Arguments.of(naam, verzoek, status, foutcode, detail)

        @JvmStatic
        fun gevallen(): Stream<Arguments> = Stream.of(
            geval("onbekend bericht", 404, Foutcode.BERICHT_ONBEKEND, DetailVerwachting.Bevat(MAGAZIJN)) {
                given().header(ONTVANGER_HEADER, ONTVANGER).get("$BASIS/berichten/$BERICHT_ID")
            },
            geval("ontbrekende ontvanger", 400, Foutcode.ONGELDIG_VERZOEK, DetailVerwachting.Bevat("must not be null")) {
                given().get("$BASIS/berichten")
            },
            // Afgebroken JSON vertaalt Quarkus al vóór de mapper-keuze naar een kale 400; de
            // standaardmelding daarvan voegt niets toe, dus geen detail.
            geval("afgebroken JSON", 400, Foutcode.ONGELDIG_VERZOEK, DetailVerwachting.Afwezig) {
                given().contentType(ContentType.JSON).body("""{"afzender":""").post("$BASIS/aanleveringen")
            },
            geval("JSON die niet past", 400, Foutcode.ONGELDIG_VERZOEK, DetailVerwachting.Bevat("JSON")) {
                given().contentType(ContentType.JSON).body("""{"afzender": 42}""").post("$BASIS/aanleveringen")
            },
            geval("onbekend pad onder een bericht", 404, Foutcode.NIET_GEVONDEN, vast(404)) {
                given().header(ONTVANGER_HEADER, ONTVANGER).get("$BASIS/berichten/$BERICHT_ID/bijlagen/$BERICHT_ID/x")
            },
            geval("onbekend pad in de API", 404, Foutcode.NIET_GEVONDEN, vast(404)) {
                given().get("$BASIS/bestaat-niet")
            },
            geval("bericht-id dat geen UUID is", 404, Foutcode.NIET_GEVONDEN, vast(404)) {
                given().header(ONTVANGER_HEADER, ONTVANGER).get("$BASIS/berichten/geen-uuid")
            },
            geval("onbekend magazijn", 404, Foutcode.NIET_GEVONDEN, DetailVerwachting.Bevat("00000009000000009999")) {
                given().header(ONTVANGER_HEADER, ONTVANGER).get("/magazijn/00000009000000009999/api/v1/berichten")
            },
            geval("onbekend magazijn op het beheerpad", 404, Foutcode.NIET_GEVONDEN, DetailVerwachting.Bevat("OIN")) {
                given().contentType(ContentType.JSON).body("""{"modus": "STUK"}""")
                    .put("/beheer/magazijnen/00000009000000009999/gedrag")
            },
            geval("methode niet toegestaan", 405, Foutcode.ONGELDIG_VERZOEK, vast(405)) {
                given().header(ONTVANGER_HEADER, ONTVANGER).put("$BASIS/berichten")
            },
            geval("Accept niet te leveren", 406, Foutcode.ONGELDIG_VERZOEK, vast(406)) {
                given().header(ONTVANGER_HEADER, ONTVANGER).accept("application/xml").get("$BASIS/berichten")
            },
            geval("Content-Type niet ondersteund", 415, Foutcode.ONGELDIG_VERZOEK, vast(415)) {
                given().contentType(ContentType.TEXT).body("geen json").post("$BASIS/aanleveringen")
            },
        )
    }
}
