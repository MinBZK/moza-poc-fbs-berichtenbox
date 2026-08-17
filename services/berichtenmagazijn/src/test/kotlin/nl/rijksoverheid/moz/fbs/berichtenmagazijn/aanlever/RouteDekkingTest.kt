package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import io.restassured.http.Method
import io.swagger.v3.parser.OpenAPIV3Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
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
 * De assertie is bewust smal. 405 betekent altijd dat het pad wél bestaat maar de methode er niet
 * op zit — precies de breuk hierboven. 404 is alleen bewijs van een ontbrekende route als het pad
 * geen parameters heeft; met een parameter is 404 juist het normale antwoord op een onbekend id.
 * Alle overige statussen (400 bij ontbrekende invoer, 401, 415, 500) bewijzen dat de route
 * gevonden is en zeggen niets over de dekking.
 */
@QuarkusTest
class RouteDekkingTest {

    companion object {
        private const val SPEC = "openapi/berichtenmagazijn-api.yaml"

        /** Gelijk aan `servers.url` in de spec; de resources dragen dit voorvoegsel via `quarkus.rest.path`. */
        private const val BASIS_PAD = "/api/v1"

        /** Vastgelegd zodat een verdwenen pad door een halve spec-parse niet stil de dekking verkleint. */
        private const val PADEN = 4
        private const val OPERATIES = 6

        /**
         * Methodes die geen enkel pad in onze specs declareert; de eerste bruikbare dient als
         * sonde. Geen HEAD: JAX-RS leidt die af van GET, dus een pad met een GET antwoordt met
         * de GET-status en nooit met 405 — dan zou de sonde een bestaande route als verdwenen
         * aanmerken. Geen OPTIONS om dezelfde reden: die handelt de container zelf af.
         */
        private val SONDE_METHODES = listOf("TRACE", "PUT")

        @JvmStatic
        fun padenUitDeSpec(): List<Arguments> {
            val spec = requireNotNull(OpenAPIV3Parser().read(SPEC)) { "Spec $SPEC niet gevonden op het test-classpath" }

            return spec.paths.map { (pad, item) ->
                Arguments.of(pad, item.readOperationsMap().keys.map { it.name }.toSet())
            }
        }

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
            assertNotEquals(
                404,
                status,
                "$methode $pad geeft 404 terwijl het pad geen parameters heeft: er is geen route voor",
            )
        }
    }

    /**
     * Op een pad mét parameters is 404 het normale antwoord op een onbekend id, dus daar zegt de
     * statuscode niets over de routering. Een methode die de spec níét noemt wél: bestaat de
     * route, dan antwoordt de router met 405; is de route verdwenen, dan blijft het 404. Zo is
     * ook voor die paden vast te stellen dát ze geregistreerd zijn.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("padenUitDeSpec")
    fun `elk pad uit de spec is als route geregistreerd`(pad: String, gespecificeerdeMethodes: Set<String>) {
        val ongebruikt = SONDE_METHODES.firstOrNull { it !in gespecificeerdeMethodes }
            ?: fail("$pad declareert alle sonde-methodes; kies er een die de spec niet gebruikt")
        val status = RestAssured.given()
            .`when`()
            .request(Method.valueOf(ongebruikt), BASIS_PAD + metIngevuldeParameters(pad))
            .then()
            .extract()
            .statusCode()

        // Precies 405, niet "alles behalve 404": dat is wat een bestaande route antwoordt op een
        // methode die de spec niet noemt, en alleen zo is de sonde zelf falsifieerbaar.
        assertEquals(
            405,
            status,
            "$ongebruikt $pad hoort 405 te geven; 404 betekent dat er geen route voor dit pad is",
        )
    }

    /** Een willekeurig, geldig gevormd id: het gaat om de routering, niet om een bestaand bericht. */
    private fun metIngevuldeParameters(pad: String) =
        Regex("""\{[^}]+}""").replace(pad) { UUID.randomUUID().toString() }

    /**
     * De controlegroep bij de sonde hierboven. Die leunt erop dat een verdwenen route 404 geeft
     * in plaats van 405; klopt dat niet, dan slaagt de sonde voor élk pad — ook voor paden die
     * niet bestaan — en bewijst hij niets. Dat is precies bij de paden mét parameters, waar de
     * eerste toets al blind is.
     */
    @Test
    fun `de sonde geeft 404 op een pad dat niet bestaat`() {
        listOf("$BASIS_PAD/bestaat-niet", "$BASIS_PAD/berichten/${UUID.randomUUID()}/bestaat-niet").forEach { pad ->
            val status = RestAssured.given().`when`().request(Method.TRACE, pad).then().extract().statusCode()

            assertEquals(404, status, "$pad bestaat niet en hoort 404 te geven, anders bewijst de sonde niets")
        }
    }

    /**
     * De spec-parser geeft bij een gedeeltelijk oplosbare spec een niet-null document terug met
     * mínder paden. De test blijft dan groen met minder gevallen dan bedoeld; dit getal dwingt af
     * dat een pad erbij of eraf een bewuste aanpassing is.
     */
    @Test
    fun `de spec levert het verwachte aantal paden en operaties`() {
        assertEquals(PADEN, padenUitDeSpec().size, "aantal paden uit de spec")
        assertEquals(OPERATIES, routesUitDeSpec().size, "aantal operaties uit de spec")
    }
}
