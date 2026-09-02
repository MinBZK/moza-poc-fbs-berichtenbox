package nl.rijksoverheid.moz.fbs.magazijnsimulator

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * De klok komt uit CDI zodat een test hem kan vastzetten — anders is elk tijdstip in een antwoord
 * alleen op "niet leeg" te toetsen, en komt een bericht dat het aanlevertijdstip in `gewijzigdOp`
 * zet ongemerkt door de suite.
 *
 * Eigen profiel: een vaste klok voor de hele applicatie zou de andere tests een stilstaande tijd
 * geven, en de seed leidt zijn tijdstippen daarvan af.
 */
@QuarkusTest
@TestProfile(KlokTest.VasteKlok::class)
class KlokTest : MagazijnTestBasis() {

    class VasteKlok : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(VasteKlokProducer::class.java)
    }

    @Alternative
    @Priority(1)
    @Singleton
    class VasteKlokProducer {

        @Produces
        @ApplicationScoped
        fun clock(): Clock = Clock.fixed(MOMENT, ZoneOffset.UTC)
    }

    @Test
    fun `het tijdstip van ontvangst komt van de klok en niet van een eigen aflezing`() {
        val berichtId = leverAan()

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(200)
            .body("tijdstipOntvangst", equalTo(MOMENT.toString()))
    }

    @Test
    fun `het tijdstip van een statuswijziging komt ook van de klok`() {
        val berichtId = leverAan()

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .contentType("application/merge-patch+json")
            .body("""{"gelezen": true}""")
            .`when`().patch("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(200)
            .body("status.gewijzigdOp", equalTo(MOMENT.toString()))
    }

    private fun leverAan(): String = given()
        .contentType(ContentType.JSON)
        .body(
            """
            {
              "afzender": "$MAGAZIJN",
              "ontvanger": {"type": "KVK", "waarde": "90000001"},
              "onderwerp": "Met een vaste klok",
              "inhoud": "Inhoud van een demo-bericht."
            }
            """.trimIndent(),
        )
        .`when`().post("$BASIS/aanleveringen")
        .then()
        .statusCode(201)
        .extract().path("berichtId")

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000001"
        const val MAGAZIJN = "00000009000000000001"
        const val BASIS = "/magazijn/$MAGAZIJN/api/v1"

        val MOMENT: Instant = Instant.parse("2026-09-02T10:00:00Z")
    }
}
