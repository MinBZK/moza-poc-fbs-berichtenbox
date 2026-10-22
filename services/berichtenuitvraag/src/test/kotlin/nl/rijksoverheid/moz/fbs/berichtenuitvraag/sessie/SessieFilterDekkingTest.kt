package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.Method
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.parser.OpenAPIV3Parser
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecacheProfile
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID

/**
 * Het [SessieFilter] en de spec beschrijven dezelfde verzameling: elke operatie met het
 * securityschema `sessie` vereist een sessie, en elke operatie zonder staat op de vrijstellingslijst.
 * Afgeleid uit de spec, zodat een nieuw endpoint of een vergeten `security`-blok hier faalt, en
 * niet pas bij een afnemer die zonder sessie bij berichten kan.
 *
 * Twee lagen: de classificatie van het filter per operatie, en het echte antwoord op een verzoek
 * zonder `X-Sessie`. De tweede vangt een pad dat de router anders normaliseert dan de
 * classificatie verwacht.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class SessieFilterDekkingTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    @BeforeEach
    fun reset() {
        sessiecache.reset()
    }

    /** Eén operatie uit de spec: methode, pad met ingevulde parameters, en of hij de sessie vereist. */
    class SpecOperatie(val methode: String, val pad: String, val vereistSessie: Boolean, val mediatype: String?) {
        override fun toString() = "$methode $pad"
    }

    companion object {
        private const val SPEC = "openapi/berichtenuitvraag-api.yaml"
        private const val BASIS_PAD = "/api/v1"
        private const val SCHEMA = "sessie"
        private const val ONTVANGER = "BSN:999990019"
        private const val MAGAZIJN = "00000001003214345000"

        /** Vastgelegd zodat een halve spec-parse de dekking niet stil verkleint. */
        private const val OPERATIES = 14

        @JvmStatic
        fun operaties(): List<SpecOperatie> {
            val spec = requireNotNull(OpenAPIV3Parser().read(SPEC)) { "Spec $SPEC niet gevonden op het test-classpath" }
            val standaard = spec.security.orEmpty()

            return spec.paths.flatMap { (pad, item) ->
                item.readOperationsMap().map { (methode, operatie) ->
                    SpecOperatie(methode.name, pad, vereistSessie(operatie.security ?: standaard), mediatype(operatie))
                }
            }
        }

        @JvmStatic
        fun beveiligd(): List<SpecOperatie> = operaties().filter { it.vereistSessie }

        @JvmStatic
        fun vrijgesteld(): List<SpecOperatie> = operaties().filterNot { it.vereistSessie }

        @JvmStatic
        fun verwachteVereisten(): List<Arguments> = listOf(
            Arguments.of("GET", "/berichten/_volgen", Sessievereiste.LOPEND),
            Arguments.of("GET", "/sessies/huidige", Sessievereiste.LOPEND),
            Arguments.of("HEAD", "/sessies/huidige", Sessievereiste.LOPEND),
            Arguments.of("DELETE", "/sessies/huidige", Sessievereiste.ALLEEN_VORM),
            Arguments.of("POST", "/sessies/huidige/_verlengen", Sessievereiste.VERLENGEN),
            Arguments.of("GET", "/berichten", Sessievereiste.VERLENGEN),
            Arguments.of("POST", "/sessies", Sessievereiste.GEEN),
            // Een pad dat (nog) niet in de spec staat, valt onder de standaard: verlengen, dus een sessie.
            Arguments.of("GET", "/iets-nieuws", Sessievereiste.VERLENGEN),
            Arguments.of("GET", "/sessies/huidige/iets-nieuws", Sessievereiste.VERLENGEN),
        )

        private fun vereistSessie(eisen: List<io.swagger.v3.oas.models.security.SecurityRequirement>): Boolean =
            eisen.any { SCHEMA in it.keys }

        private fun mediatype(operatie: Operation): String? = operatie.requestBody?.content?.keys?.firstOrNull()
    }

    @Test
    fun `de spec heeft het verwachte aantal operaties, met en zonder sessie`() {
        val alle = operaties()

        assertEquals(OPERATIES, alle.size, alle.toString())
        assertEquals(
            setOf("POST /sessies", "POST /aanmeldingen", "GET /openapi.json"),
            vrijgesteld().map { it.toString() }.toSet(),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("beveiligd")
    fun `elke operatie met het sessieschema valt onder de sessiecontrole`(operatie: SpecOperatie) {
        assertNotEquals(Sessievereiste.GEEN, SessieFilter.vereiste(operatie.methode, operatie.pad))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vrijgesteld")
    fun `elke operatie zonder sessieschema staat op de vrijstellingslijst`(operatie: SpecOperatie) {
        assertEquals(Sessievereiste.GEEN, SessieFilter.vereiste(operatie.methode, operatie.pad))
    }

    @ParameterizedTest(name = "{0} {1} vereist {2}")
    @MethodSource("verwachteVereisten")
    fun `het filter maakt het juiste onderscheid tussen verlengen, lezen en alleen de vorm`(
        methode: String,
        pad: String,
        verwacht: Sessievereiste,
    ) {
        assertEquals(verwacht, SessieFilter.vereiste(methode, pad))
        // JAX-RS levert het pad met of zonder voorloop-slash.
        assertEquals(verwacht, SessieFilter.vereiste(methode, pad.removePrefix("/")))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("beveiligd")
    fun `een verzoek zonder X-Sessie op een beveiligde operatie krijgt 401 en raakt geen sessie`(operatie: SpecOperatie) {
        verzoek(operatie)
            .then()
            .statusCode(401)
            .header("WWW-Authenticate", "X-Sessie realm=\"berichtenuitvraag\"")
            .body("type", equalTo("urn:fbs:fout:sessie-ongeldig"))

        assertTrue(sessiecache.geraakteSessies.isEmpty() && sessiecache.gelezenSessies.isEmpty())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vrijgesteld")
    fun `een verzoek zonder X-Sessie op een vrijgestelde operatie krijgt geen 401`(operatie: SpecOperatie) {
        val status = verzoek(operatie).then().extract().statusCode()

        assertNotEquals(401, status, "$operatie vraagt toch een sessie")
    }

    private fun verzoek(operatie: SpecOperatie) = given()
        .header("X-Ontvanger", ONTVANGER)
        .apply { operatie.mediatype?.let { contentType(it).body("{}") } }
        .`when`()
        .request(Method.valueOf(operatie.methode), BASIS_PAD + ingevuld(operatie.pad))

    // De magazijnId hoort bij elk pad met een bericht; de rest van de parameters is een willekeurig id.
    private fun ingevuld(pad: String): String {
        val metIds = Regex("""\{[^}]+}""").replace(pad) { UUID.randomUUID().toString() }

        return if (pad.startsWith("/berichten/{")) "$metIds?magazijnId=$MAGAZIJN" else metIds
    }
}
