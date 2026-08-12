package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import io.restassured.config.HttpClientConfig
import io.restassured.config.RestAssuredConfig
import io.restassured.http.Method
import io.swagger.v3.parser.OpenAPIV3Parser
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID

/**
 * Toetst dat élk pad uit de OpenAPI-spec ook daadwerkelijk bij een resource aankomt.
 *
 * De bestaande contracttests valideren request en response tegen het schema, maar alleen voor de
 * paden die ze zelf aanroepen; een pad dat door de routering niet meer gevonden wordt, viel
 * daarbuiten. Dat is precies wat er misging toen de code-generator de paden anders over de
 * gegenereerde interfaces verdeelde: het aanlever-endpoint kwam in de routeringsboom onder een
 * andere class terecht en gaf 405, terwijl de spec ongewijzigd was.
 *
 * De sessiecache staat op de mock: het gaat om de routering, en het ophaal-endpoint moet zijn
 * stroom netjes afsluiten in plaats van open te blijven staan.
 *
 * De assertie is bewust smal. 405 betekent altijd dat het pad wél bestaat maar de methode er niet
 * op zit — precies de breuk hierboven. 404 is alleen bewijs van een ontbrekende route als het pad
 * geen parameters heeft; met een parameter is 404 juist het normale antwoord op een onbekend id.
 * Alle overige statussen (400 bij ontbrekende invoer, 401, 415, 500) bewijzen dat de route
 * gevonden is en zeggen niets over de dekking.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class RouteDekkingTest {

    companion object {
        private const val SPEC = "openapi/berichtenuitvraag-api.yaml"

        /** Gelijk aan `servers.url` in de spec; de resources dragen dit voorvoegsel via `ApiInfo.BASE_PATH`. */
        private const val BASIS_PAD = "/api/v1"

        @JvmStatic
        fun routesUitDeSpec(): List<Arguments> {
            val spec = requireNotNull(OpenAPIV3Parser().read(SPEC)) { "Spec $SPEC niet gevonden op het test-classpath" }

            return spec.paths.flatMap { (pad, item) ->
                item.readOperationsMap().map { (methode, _) -> Arguments.of(methode.name, pad) }
            }
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("routesUitDeSpec")
    fun `elk pad uit de spec komt bij een resource aan`(methode: String, pad: String) {
        val heeftPadParameter = "{" in pad
        val status = RestAssured.given()
            // Een afgewezen request wordt beantwoord zónder de body te lezen; zonder deze
            // grens blijft de client schrijven tot iets anders ingrijpt. Dat liet een
            // CI-job ooit zes uur doorlopen voordat de runner hem afkapte.
            .config(
                RestAssuredConfig.config().httpClient(
                    HttpClientConfig.httpClientConfig()
                        .setParam("http.socket.timeout", SOCKET_TIMEOUT_MS)
                        .setParam("http.connection.timeout", CONNECT_TIMEOUT_MS),
                ),
            )
            .`when`()
            .request(Method.valueOf(methode), BASIS_PAD + metIngevuldeParameters(pad))
            .then()
            .extract()
            .statusCode()

        assertNotEquals(
            405,
            status,
            "$methode $pad geeft 405: het pad bestaat, maar deze methode zit er niet op — " +
                "een routerings- of generator-wijziging heeft de operatie onbereikbaar gemaakt",
        )

        if (!heeftPadParameter) {
            assertTrue(
                status != 404,
                "$methode $pad geeft 404 terwijl het pad geen parameters heeft: er is geen route voor",
            )
        }
    }

    /** Een willekeurig, geldig gevormd id: het gaat om de routering, niet om een bestaand bericht. */
    private fun metIngevuldeParameters(pad: String) =
        Regex("""\{[^}]+}""").replace(pad) { UUID.randomUUID().toString() }
}

private const val SOCKET_TIMEOUT_MS = 30_000
private const val CONNECT_TIMEOUT_MS = 10_000
