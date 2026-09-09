package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Een opslag die leeggemaakt is, wordt opnieuw gevuld.
 *
 * Dat is het geval waar de omgeving het van moet hebben: een deployment waarvan de database opnieuw
 * is aangemaakt komt terug met een lege opslag, en de eerstvolgende start hoort daar weer post in te
 * zetten. Ruimt daarom wél op vooraf — deze klasse toetst niet wat het opstarten achterliet, maar
 * wat er gebeurt zodra de opslag leeg is.
 *
 * Een eigen ontvanger, zodat berichten uit een andere testklasse deze telling niet raken: de
 * bericht-id's zijn afgeleid van magazijn, ontvanger en volgnummer.
 */
@QuarkusTest
@TestProfile(BasisvullingOpnieuwTest.MetBasisvulling::class)
class BasisvullingOpnieuwTest : MagazijnTestBasis() {

    @Inject
    lateinit var basisvulling: Basisvulling

    class MetBasisvulling : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "magazijnsimulator.basisvulling.ontvangers" to ONTVANGER,
            "magazijnsimulator.basisvulling.berichten-per-magazijn" to "$AANTAL",
            "magazijnsimulator.basisvulling.bijlage-elke" to "0",
        )
    }

    @Test
    fun `een lege opslag krijgt zijn basisvulling terug`() {
        assertEquals(Basisvulling.Uitkomst.GEPLAATST, basisvulling.vulIndienLeeg())

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/$MAGAZIJN/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten", hasSize<Any>(AANTAL))
    }

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000078"
        const val AANTAL = 3
        const val MAGAZIJN = "00000009000000000001"
    }
}
