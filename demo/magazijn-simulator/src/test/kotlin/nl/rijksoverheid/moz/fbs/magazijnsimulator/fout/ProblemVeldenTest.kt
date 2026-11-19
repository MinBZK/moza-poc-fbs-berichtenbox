package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
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
        verzoek: (RequestSpecification) -> Response,
        status: Int,
        foutcode: Foutcode,
        detail: DetailVerwachting,
    ) {
        assertProblemVelden(verzoek(given()), status, foutcode, detail)
    }

    /**
     * Dezelfde frameworkfouten, nu op een operatie die de spec kent en met de validator ertussen. Zo
     * merkt een test het ook als het antwoord buiten de veldenset om van de spec afwijkt, zoals
     * een ontbrekende `API-Version`. Alleen het antwoord telt; het verzoek schendt de spec met opzet.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("gevallenOpSpecOperaties")
    fun `een frameworkfout op een operatie uit de spec voldoet aan de spec`(
        @Suppress("UNUSED_PARAMETER") naam: String,
        verzoek: (RequestSpecification) -> Response,
        status: Int,
        foutcode: Foutcode,
        detail: DetailVerwachting,
    ) {
        assertProblemVelden(verzoek(given().filter(alleenAntwoordValidatie)), status, foutcode, detail)
    }

    /** Een storing komt uit het gedrag-filter en niet uit een mapper; ook die mag geen extra veld dragen. */
    @Test
    fun `een gesimuleerde storing heeft geen extra veld`() {
        assertTrue(magazijnen.stelGedragBij(MAGAZIJN, Gedrag(GedragModus.STUK, latencyP50Ms = 0, latencyP95Ms = 0)))

        assertProblemVelden(
            given().header(ONTVANGER_HEADER, ONTVANGER).get("$BASIS/berichten"),
            Gedrag.STANDAARD_FOUT_STATUS,
            Foutcode.TIJDELIJK_NIET_BESCHIKBAAR,
            DetailVerwachting.Precies("Dit magazijn is ingesteld op stuk"),
        )
    }

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000001"
        const val MAGAZIJN = "00000009000000000003"
        const val ONBEKEND_MAGAZIJN = "00000009000000009999"
        const val BASIS = "/magazijn/$MAGAZIJN/api/v1"
        const val BERICHT_ID = "11111111-2222-3333-4444-555555555555"

        val alleenAntwoordValidatie = OpenApiValidationFilter(
            OpenApiInteractionValidator
                .createForSpecificationUrl("openapi/berichtenmagazijn-api.yaml")
                .withBasePathOverride(BASIS)
                .withLevelResolver(
                    LevelResolver.create()
                        .withLevel("validation.request", ValidationReport.Level.IGNORE)
                        // Zonder deze twee valideert de filter niets als het pad niet in de spec
                        // gevonden wordt, en blijft de test stil groen.
                        .withLevel("validation.request.path.missing", ValidationReport.Level.ERROR)
                        .withLevel("validation.request.operation.notAllowed", ValidationReport.Level.ERROR)
                        .build(),
                )
                .build(),
        )

        private fun geval(
            naam: String,
            status: Int,
            foutcode: Foutcode,
            detail: DetailVerwachting,
            verzoek: (RequestSpecification) -> Response,
        ): Arguments = Arguments.of(naam, verzoek, status, foutcode, detail)

        private val geenUuid = geval(
            "bericht-id dat geen UUID is",
            400,
            Foutcode.ONGELDIG_VERZOEK,
            DetailVerwachting.Precies(FrameworkfoutDetail.PARAMETER_ONGELDIG),
        ) { it.header(ONTVANGER_HEADER, ONTVANGER).get("$BASIS/berichten/geen-uuid") }

        private val acceptNietTeLeveren = geval(
            "Accept niet te leveren",
            406,
            Foutcode.ONGELDIG_VERZOEK,
            DetailVerwachting.Precies(FrameworkfoutDetail.ACCEPT_NIET_TE_LEVEREN),
        ) { it.header(ONTVANGER_HEADER, ONTVANGER).accept("application/xml").get("$BASIS/berichten") }

        private val mediatypeNietOndersteund = geval(
            "Content-Type niet ondersteund",
            415,
            Foutcode.ONGELDIG_VERZOEK,
            DetailVerwachting.Precies(FrameworkfoutDetail.MEDIATYPE_NIET_ONDERSTEUND),
        ) { it.contentType(ContentType.TEXT).body("geen json").post("$BASIS/aanleveringen") }

        private val pageGeenGetal = geval(
            "page dat geen getal is",
            400,
            Foutcode.ONGELDIG_VERZOEK,
            DetailVerwachting.Precies(FrameworkfoutDetail.PARAMETER_ONGELDIG),
        ) { it.header(ONTVANGER_HEADER, ONTVANGER).get("$BASIS/berichten?page=abc") }

        @JvmStatic
        fun gevallenOpSpecOperaties(): Stream<Arguments> =
            Stream.of(geenUuid, pageGeenGetal, acceptNietTeLeveren, mediatypeNietOndersteund)

        @JvmStatic
        fun gevallen(): Stream<Arguments> = Stream.of(
            geval("onbekend bericht", 404, Foutcode.BERICHT_ONBEKEND, DetailVerwachting.Bevat(MAGAZIJN)) {
                it.header(ONTVANGER_HEADER, ONTVANGER).get("$BASIS/berichten/$BERICHT_ID")
            },
            // De melding van Bean Validation ligt hier niet vast: dit gaat over de velden.
            geval("ontbrekende ontvanger", 400, Foutcode.ONGELDIG_VERZOEK, DetailVerwachting.Aanwezig) {
                it.get("$BASIS/berichten")
            },
            // Afgebroken JSON vertaalt Quarkus al vóór de mapper-keuze naar een gewone 400. Die is
            // niet te onderscheiden van een eigen 400, en de standaardmelding geeft geen detail.
            geval("afgebroken JSON", 400, Foutcode.ONGELDIG_VERZOEK, DetailVerwachting.Afwezig) {
                it.contentType(ContentType.JSON).body("""{"afzender":""").post("$BASIS/aanleveringen")
            },
            geval("JSON die niet past", 400, Foutcode.ONGELDIG_VERZOEK, DetailVerwachting.Bevat("JSON")) {
                it.contentType(ContentType.JSON).body("""{"afzender": 42}""").post("$BASIS/aanleveringen")
            },
            geval(
                "onbekend pad onder een bericht",
                404,
                Foutcode.NIET_GEVONDEN,
                DetailVerwachting.Precies(FrameworkfoutDetail.PAD_ONBEKEND),
            ) { it.header(ONTVANGER_HEADER, ONTVANGER).get("$BASIS/berichten/$BERICHT_ID/bijlagen/$BERICHT_ID/x") },
            geval(
                "onbekend pad in de API",
                404,
                Foutcode.NIET_GEVONDEN,
                DetailVerwachting.Precies(FrameworkfoutDetail.PAD_ONBEKEND),
            ) { it.get("$BASIS/bestaat-niet") },
            geenUuid,
            pageGeenGetal,
            geval(
                "onbekend magazijn",
                404,
                Foutcode.NIET_GEVONDEN,
                DetailVerwachting.Bevat(ONBEKEND_MAGAZIJN),
            ) { it.header(ONTVANGER_HEADER, ONTVANGER).get("/magazijn/$ONBEKEND_MAGAZIJN/api/v1/berichten") },
            // Het OIN is publiek en hoort voluit in de melding te staan.
            geval(
                "onbekend magazijn op het beheerpad",
                404,
                Foutcode.NIET_GEVONDEN,
                DetailVerwachting.Precies("Geen gesimuleerd magazijn met OIN $ONBEKEND_MAGAZIJN"),
            ) {
                it.contentType(ContentType.JSON).body("""{"modus": "STUK"}""")
                    .put("/beheer/magazijnen/$ONBEKEND_MAGAZIJN/gedrag")
            },
            geval(
                "methode niet toegestaan",
                405,
                Foutcode.ONGELDIG_VERZOEK,
                DetailVerwachting.Precies(FrameworkfoutDetail.METHODE_NIET_TOEGESTAAN),
            ) { it.header(ONTVANGER_HEADER, ONTVANGER).put("$BASIS/berichten") },
            acceptNietTeLeveren,
            mediatypeNietOndersteund,
        )
    }
}
