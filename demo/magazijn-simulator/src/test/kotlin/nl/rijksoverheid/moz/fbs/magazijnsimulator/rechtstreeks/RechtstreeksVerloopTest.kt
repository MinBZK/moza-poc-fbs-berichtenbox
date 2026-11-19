package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.VasteKlokProducer
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksBerichtenTest.Companion.ONGELDIG_TOKEN
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksBerichtenTest.Companion.ONTVANGER
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksBerichtenTest.Companion.leverAan
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.App
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.MAGAZIJN_A
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.basis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.RechtstreeksTest.Companion.valideer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Een magazijnsessie die verloopt. Eigen profiel en dus een eigen applicatie: de klok verzetten
 * laat ook de challenges en sessies verlopen die andere tests in dezelfde applicatie nog nodig
 * hebben.
 */
@QuarkusTest
@TestProfile(RechtstreeksVerloopTest.Profiel::class)
class RechtstreeksVerloopTest : MagazijnTestBasis() {

    class Profiel : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = RechtstreeksTest.Profiel().configOverrides
    }

    @Inject
    lateinit var klokProducer: VasteKlokProducer

    @AfterEach
    fun zetKlokTerug() {
        klokProducer.klok.verschuiving = Duration.ZERO
    }

    @Test
    fun `een verlopen sessie geeft op elk berichtpad 401`() {
        val berichtId = leverAan(ONTVANGER, "Verloopt")
        val bearer = "Bearer ${App(klokProducer.klok.instant()).sessie(MAGAZIJN_A)}"

        klokProducer.klok.verschuiving = SESSIEDUUR.minusSeconds(1)
        given().header("Authorization", bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/berichten/$berichtId").then().statusCode(200)

        klokProducer.klok.verschuiving = SESSIEDUUR

        listOf("berichten", "berichten/$berichtId").forEach { pad ->
            valideer(MAGAZIJN_A).header("Authorization", bearer).get("${basis(MAGAZIJN_A)}/rechtstreeks/$pad")
                .then().statusCode(401).header("WWW-Authenticate", ONGELDIG_TOKEN)
        }
    }

    private companion object {
        val SESSIEDUUR: Duration = Duration.ofMinutes(15)
    }
}
