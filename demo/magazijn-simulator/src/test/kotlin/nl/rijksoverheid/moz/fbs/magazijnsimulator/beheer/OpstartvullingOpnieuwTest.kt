package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.GesimuleerdeMagazijnen
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BulkOpslag
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock

/**
 * Wat er gebeurt zodra er magazijnen zonder post zijn.
 *
 * Dat is het geval waar de omgeving het van moet hebben: een deployment waarvan de database opnieuw
 * is aangemaakt komt terug met een lege opslag, en een ronde die halverwege afbrak laat een deel van
 * de magazijnen leeg achter. Ruimt daarom wél op vooraf — deze klasse toetst niet wat het opstarten
 * achterliet, maar wat een volgende ronde met een (gedeeltelijk) lege opslag doet.
 */
@QuarkusTest
@TestProfile(OpstartvullingOpnieuwTest.MetOpstartvulling::class)
class OpstartvullingOpnieuwTest : MagazijnTestBasis() {

    @Inject
    lateinit var opstartvulling: Opstartvulling

    @Inject
    lateinit var magazijnen: GesimuleerdeMagazijnen

    @Inject
    lateinit var bulk: BulkOpslag

    @Inject
    lateinit var klok: Clock

    class MetOpstartvulling : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "magazijnsimulator.opstartvulling.ontvangers" to ONTVANGER,
            "magazijnsimulator.opstartvulling.berichten-per-magazijn" to "$AANTAL",
            "magazijnsimulator.opstartvulling.bijlage-elke" to "0",
        )
    }

    @Test
    fun `een lege opslag krijgt zijn vulling terug`() {
        val uitkomst = assertInstanceOf(Opstartvulling.Uitkomst.Geplaatst::class.java, opstartvulling.vulOntbrekende())

        assertEquals(magazijnen.alle().size, uitkomst.seed.magazijnen)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/$MAGAZIJN/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten", hasSize<Any>(AANTAL))
    }

    /**
     * Het geval van een ronde die halverwege afbrak: één magazijn vol, de rest leeg. Zou de
     * beslissing op "staat er érgens iets" gaan, dan bleven die andere magazijnen voorgoed leeg
     * terwijl de log "al gevuld" meldt — en dan is de omgeving stukker dan zonder deze vulling.
     */
    @Test
    fun `een half gevulde opslag wordt afgemaakt zonder het gevulde magazijn te raken`() {
        val eerste = magazijnen.alle().minByOrNull { it.oin }!!

        // Alleen het eerste magazijn vullen, alsof de vorige ronde daarna afbrak.
        bulk.voegToe(
            eerste.dbId,
            DemoBerichten.voor(
                magazijnOin = eerste.oin,
                ontvanger = Identificatie.uitHeader(ANDERE_ONTVANGER),
                aantal = 1,
                bijlageElke = 0,
                nu = klok.instant(),
            ),
        )

        val uitkomst = assertInstanceOf(Opstartvulling.Uitkomst.Geplaatst::class.java, opstartvulling.vulOntbrekende())

        assertEquals(magazijnen.alle().size - 1, uitkomst.seed.magazijnen, "het gevulde magazijn hoort overgeslagen")

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/${eerste.oin}/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten", hasSize<Any>(0))
    }

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000078"
        const val ANDERE_ONTVANGER = "KVK:90000079"
        const val AANTAL = 3
        const val MAGAZIJN = "00000009000000000001"
    }
}
