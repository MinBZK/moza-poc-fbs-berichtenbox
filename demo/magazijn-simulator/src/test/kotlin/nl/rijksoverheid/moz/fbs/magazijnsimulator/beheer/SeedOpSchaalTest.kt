package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Het vullen van een demo op de schaal waarvoor de simulator bestaat: honderd magazijnen.
 *
 * De eis is niet academisch. Wie een demonstratie voorbereidt, doet dat vlak van tevoren; duurt het
 * minuten, dan gebeurt het niet en draait de demo op wat er toevallig nog stond. Losse aanleveringen
 * via de gewone API zouden hier achtduizend rondjes naar de database kosten, en dat is precies de
 * reden dat het beheerpad zijn eigen bulk-opslag heeft.
 */
@QuarkusTest
@TestProfile(SeedOpSchaalTest.HonderdMagazijnen::class)
class SeedOpSchaalTest {

    /** Honderd magazijnen, opgebouwd zoals het generatiescript ze straks schrijft. */
    class HonderdMagazijnen : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            (1..AANTAL).flatMap { i ->
                val oin = "0000000900000000%04d".format(i)

                listOf(
                    "magazijnsimulator.magazijnen.\"$oin\".naam" to "Demo-magazijn $i",
                    "magazijnsimulator.magazijnen.\"$oin\".index" to "$i",
                )
            }.toMap()
    }

    @Test
    fun `honderd magazijnen vullen kost seconden en geen minuten`() {
        val uitkomst = given()
            .contentType(ContentType.JSON)
            .body("""{"ontvangers": ["KVK:90000001"], "berichtenPerMagazijn": 20, "bijlageElke": 4}""")
            .`when`().post("/beheer/seed")
            .then()
            .statusCode(200)
            .body("magazijnen", equalTo(AANTAL))
            .body("berichten", equalTo(AANTAL * 20))
            .body("bijlagen", equalTo(AANTAL * 5))
            .extract().jsonPath()

        val duurMs = uitkomst.getLong("duurMs")

        assertTrue(duurMs < MAX_DUUR_MS, "vullen duurde $duurMs ms, verwacht onder $MAX_DUUR_MS ms")
    }

    /**
     * De vastgelegde verdeling over honderd magazijnen, zoals een demo hem laat zien. Dit is de
     * enige plek waar hij op volle schaal langs de configuratie loopt in plaats van alleen in een
     * unittest.
     */
    @Test
    fun `de gedragsverdeling komt over honderd magazijnen overeen met de afspraak`() {
        val modi = given()
            .`when`().get("/beheer/magazijnen")
            .then()
            .statusCode(200)
            .extract().jsonPath()
            .getList<String>("modus")
            .groupingBy { it }
            .eachCount()

        assertTrue(modi["NORMAAL"]!! > modi.values.sum() / 2, "veruit de meeste magazijnen doen het gewoon")
        assertTrue(modi["UIT"] == 2 && modi["STUK"] == 3, "twee onbereikbaar en drie stuk, was $modi")
        assertTrue(modi["WEIGERT"] == 1 && modi["MALFORMED"] == 1, "één weigering en één onbruikbaar, was $modi")
    }

    private companion object {
        const val AANTAL = 100

        /** Ruim onder wat iemand die een demo voorbereidt nog acceptabel vindt. */
        const val MAX_DUUR_MS = 10_000L
    }
}
