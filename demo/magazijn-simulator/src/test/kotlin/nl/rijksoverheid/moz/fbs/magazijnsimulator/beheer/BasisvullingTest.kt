package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * De basisvulling die de simulator zichzelf bij het opstarten geeft.
 *
 * Zonder die vulling staat een verse omgeving — een preview, of een deployment waarvan de database
 * opnieuw is aangemaakt — met alle magazijnen op nul berichten, terwijl elk magazijn keurig
 * antwoordt. Dat is van een kapotte keten niet te onderscheiden, en het valt pas op wanneer iemand
 * de omgeving voor een demonstratie opent.
 *
 * Ruimt bewust niets op vooraf: wat deze klasse toetst is wat het opstarten heeft achtergelaten, en
 * een opruiming vóór de eerste toets zou juist dat weggooien. De ontvanger is daarom een eigen
 * nummer dat geen andere testklasse gebruikt — de bericht-id's zijn afgeleid van magazijn,
 * ontvanger en volgnummer, dus een gedeelde ontvanger zou berichten van een andere klasse in de
 * telling laten meelopen.
 */
@QuarkusTest
@TestProfile(BasisvullingTest.MetBasisvulling::class)
class BasisvullingTest {

    @Inject
    lateinit var basisvulling: Basisvulling

    /** De configuratie die het generatiescript straks meelevert: voor wie, hoeveel, en met bijlagen. */
    class MetBasisvulling : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "magazijnsimulator.basisvulling.ontvangers" to ONTVANGER,
            "magazijnsimulator.basisvulling.berichten-per-magazijn" to "$AANTAL",
            "magazijnsimulator.basisvulling.bijlage-elke" to "2",
        )
    }

    @Test
    fun `het opstarten zet berichten klaar voor de geconfigureerde ontvanger`() {
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/$MAGAZIJN/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten", hasSize<Any>(AANTAL))
    }

    @Test
    fun `een tweede ronde laat een gevulde opslag met rust`() {
        assertEquals(Basisvulling.Uitkomst.AL_GEVULD, basisvulling.vulIndienLeeg())
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

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"

        /** Eigen nummer, zodat geen enkele andere testklasse dezelfde bericht-id's aanmaakt. */
        const val ONTVANGER = "KVK:90000077"
        const val AANTAL = 2
        const val MAGAZIJN = "00000009000000000001"
        const val TWEEDE_MAGAZIJN = "00000009000000000002"
        const val DERDE_MAGAZIJN = "00000009000000000003"
    }
}
