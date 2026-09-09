package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * De vulling die de simulator zichzelf bij het opstarten geeft.
 *
 * Zonder die vulling staat een verse omgeving met alle magazijnen op nul berichten terwijl elk
 * magazijn keurig antwoordt — van een kapotte keten niet te onderscheiden.
 *
 * Ruimt bewust niets op vooraf: wat deze klasse toetst is wat het opstarten heeft achtergelaten, en
 * een opruiming vóór de eerste toets zou juist dat weggooien. Dat kan omdat een eigen `@TestProfile`
 * de applicatie herstart mét een verse database — Dev Services start per profiel een eigen
 * container. Verhuist deze klasse ooit naar het gewone profiel, of wordt `testcontainers.reuse`
 * aangezet, dan verdwijnt die garantie en meet de eerste toets wat een andere klasse achterliet.
 */
@QuarkusTest
@TestProfile(OpstartvullingTest.MetOpstartvulling::class)
class OpstartvullingTest {

    @Inject
    lateinit var opstartvulling: Opstartvulling

    /** Twee ontvangers van verschillend type: een vulling die alleen de eerste pakt, valt zo op. */
    class MetOpstartvulling : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "magazijnsimulator.opstartvulling.ontvangers" to "$ONTVANGER,$TWEEDE_ONTVANGER",
            "magazijnsimulator.opstartvulling.berichten-per-magazijn" to "$AANTAL",
            "magazijnsimulator.opstartvulling.bijlage-elke" to "$BIJLAGE_ELKE",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [MAGAZIJN, TWEEDE_MAGAZIJN, DERDE_MAGAZIJN])
    fun `elk geconfigureerd magazijn is gevuld, niet alleen het eerste`(oin: String) {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/$oin/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten", hasSize<Any>(AANTAL))
    }

    @ParameterizedTest
    @ValueSource(strings = [ONTVANGER, TWEEDE_ONTVANGER])
    fun `elke geconfigureerde ontvanger heeft post, niet alleen de eerste`(ontvanger: String) {
        given()
            .header(ONTVANGER_HEADER, ontvanger)
            .`when`().get("/magazijn/$MAGAZIJN/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten", hasSize<Any>(AANTAL))
    }

    /**
     * De bijlage-instelling moet ook echt doorgegeven worden: zonder deze toets levert een vulling
     * die altijd nul bijlagen schrijft precies dezelfde groene suite op, en mist de demo het
     * onderdeel waar bijlagen in voorkomen.
     */
    @Test
    fun `de ingestelde verhouding levert bijlagen op`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/$MAGAZIJN/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten.findAll { it.bijlagen.size() > 0 }", hasSize<Any>(AANTAL / BIJLAGE_ELKE))
            .body("berichten.find { it.bijlagen.size() > 0 }.bijlagen[0].naam", equalTo("bijlage-$BIJLAGE_ELKE.pdf"))
    }

    @Test
    fun `een tweede ronde laat een gevulde opslag met rust`() {
        assertEquals(Opstartvulling.Uitkomst.AlGevuld, opstartvulling.vulOntbrekende())
    }

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"

        /** Eigen nummers, zodat een andere testklasse deze tellingen niet kan raken. */
        const val ONTVANGER = "KVK:90000077"
        const val TWEEDE_ONTVANGER = "BSN:999993653"
        const val AANTAL = 2
        const val BIJLAGE_ELKE = 2
        const val MAGAZIJN = "00000009000000000001"
        const val TWEEDE_MAGAZIJN = "00000009000000000002"
        const val DERDE_MAGAZIJN = "00000009000000000003"
    }
}
