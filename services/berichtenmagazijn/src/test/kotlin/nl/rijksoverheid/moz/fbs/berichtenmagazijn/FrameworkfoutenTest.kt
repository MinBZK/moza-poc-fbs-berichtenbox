package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import nl.rijksoverheid.moz.fbs.common.exception.Foutcode
import nl.rijksoverheid.moz.fbs.kern.exception.FrameworkfoutDetail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Foutantwoorden die Quarkus zelf veroorzaakt: een pad dat niet bestaat, een parameter die niet te
 * lezen is, een methode die daar niet mag, een mediatype dat niet te leveren of niet te lezen is.
 *
 * Quarkus geeft daarbij een melding mee die zijn eigen werking beschrijft ("Unable to find matching
 * target resource method"). Die hoort niet in `detail`. Dit toetst het tegen het echte framework,
 * zodat een andere exception of melding na een Quarkus-upgrade niet ongemerkt naar buiten komt. Ook
 * de set velden ligt vast: het schema staat extra velden toe, dus een contracttest merkt een veld
 * te veel niet op.
 */
@QuarkusTest
class FrameworkfoutenTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("gevallen")
    fun `een frameworkfout heeft de vaste tekst en precies de velden van een foutantwoord`(
        @Suppress("UNUSED_PARAMETER") naam: String,
        verzoek: (RequestSpecification) -> Response,
        status: Int,
        foutcode: Foutcode,
        detail: String,
    ) {
        assertFrameworkfout(verzoek(given()), status, foutcode, detail)
    }

    /**
     * Dezelfde fouten, op een operatie die de spec kent en met de validator ertussen, zodat ook een
     * afwijking buiten de veldenset opvalt (zoals een ontbrekende `API-Version`). Alleen het antwoord
     * telt; het verzoek schendt de spec met opzet.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("gevallenOpSpecOperaties")
    fun `een frameworkfout op een operatie uit de spec voldoet aan de spec`(
        @Suppress("UNUSED_PARAMETER") naam: String,
        verzoek: (RequestSpecification) -> Response,
        status: Int,
        foutcode: Foutcode,
        detail: String,
    ) {
        assertFrameworkfout(verzoek(given().filter(alleenAntwoordValidatie)), status, foutcode, detail)
    }

    private fun assertFrameworkfout(antwoord: Response, status: Int, foutcode: Foutcode, detail: String) {
        assertEquals(status, antwoord.statusCode, antwoord.asString())
        assertEquals("application/problem+json", antwoord.contentType.substringBefore(';'))
        // De spec maakt de header niet verplicht, dus de validator vangt een ontbrekende niet.
        assertNotNull(antwoord.header("API-Version"), "API-Version ontbreekt")

        val velden = antwoord.jsonPath().getMap<String, Any?>("$")

        assertEquals(setOf("type", "title", "status", "detail", "instance"), velden.keys, antwoord.asString())
        assertEquals(foutcode.uri.toString(), velden["type"])
        assertEquals(detail, velden["detail"])
        assertTrue((velden["instance"] as String).startsWith("urn:uuid:"), "instance: ${velden["instance"]}")
    }

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000001"
        const val BERICHT_ID = "11111111-2222-3333-4444-555555555555"

        val alleenAntwoordValidatie = OpenApiValidationFilter(
            OpenApiInteractionValidator
                .createForSpecificationUrl("openapi/berichtenmagazijn-api.yaml")
                .withLevelResolver(
                    LevelResolver.create()
                        .withLevel("validation.request", ValidationReport.Level.IGNORE)
                        // Zonder deze twee valideert de filter niets als het pad of de methode
                        // niet in de spec staat, en blijft de test stil groen.
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
            detail: String,
            verzoek: (RequestSpecification) -> Response,
        ): Arguments = Arguments.of(naam, verzoek, status, foutcode, detail)

        private val geenUuid = geval(
            "bericht-id dat geen UUID is",
            400,
            Foutcode.ONGELDIG_VERZOEK,
            FrameworkfoutDetail.PARAMETER_ONGELDIG,
        ) { it.header(ONTVANGER_HEADER, ONTVANGER).get("/api/v1/berichten/geen-uuid") }

        private val acceptNietTeLeveren = geval(
            "Accept niet te leveren",
            406,
            Foutcode.ONGELDIG_VERZOEK,
            FrameworkfoutDetail.ACCEPT_NIET_TE_LEVEREN,
        ) { it.header(ONTVANGER_HEADER, ONTVANGER).accept("application/xml").get("/api/v1/berichten") }

        private val mediatypeNietOndersteund = geval(
            "Content-Type niet ondersteund",
            415,
            Foutcode.ONGELDIG_VERZOEK,
            FrameworkfoutDetail.MEDIATYPE_NIET_ONDERSTEUND,
        ) { it.contentType(ContentType.TEXT).body("geen json").post("/api/v1/aanleveringen") }

        private val pageGeenGetal = geval(
            "page dat geen getal is",
            400,
            Foutcode.ONGELDIG_VERZOEK,
            FrameworkfoutDetail.PARAMETER_ONGELDIG,
        ) { it.header(ONTVANGER_HEADER, ONTVANGER).get("/api/v1/berichten?page=abc") }

        @JvmStatic
        fun gevallenOpSpecOperaties(): Stream<Arguments> =
            Stream.of(geenUuid, pageGeenGetal, acceptNietTeLeveren, mediatypeNietOndersteund)

        @JvmStatic
        fun gevallen(): Stream<Arguments> = Stream.of(
            geval("onbekend pad onder een bericht", 404, Foutcode.NIET_GEVONDEN, FrameworkfoutDetail.PAD_ONBEKEND) {
                it.header(ONTVANGER_HEADER, ONTVANGER).get("/api/v1/berichten/$BERICHT_ID/bijlagen/$BERICHT_ID/x")
            },
            geval("onbekend pad in de API", 404, Foutcode.NIET_GEVONDEN, FrameworkfoutDetail.PAD_ONBEKEND) {
                it.get("/api/v1/bestaat-niet")
            },
            geenUuid,
            pageGeenGetal,
            geval(
                "methode niet toegestaan",
                405,
                Foutcode.ONGELDIG_VERZOEK,
                FrameworkfoutDetail.METHODE_NIET_TOEGESTAAN,
            ) { it.header(ONTVANGER_HEADER, ONTVANGER).put("/api/v1/berichten") },
            acceptNietTeLeveren,
            mediatypeNietOndersteund,
        )
    }
}
