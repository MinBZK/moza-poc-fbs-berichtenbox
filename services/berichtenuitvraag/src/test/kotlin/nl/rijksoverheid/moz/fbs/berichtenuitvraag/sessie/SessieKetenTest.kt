package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Het portaal als klok, door de hele keten: HTTP, filter, facade en een echte Redis. Inactiviteit
 * 2 s en een maximale duur van een minuut uit [KorteSessieKetenProfile], zodat verlopen binnen een
 * test te zien is en het plafond alleen meetelt als de start er zelf een meegaf.
 */
@QuarkusTest
@TestProfile(KorteSessieKetenProfile::class)
class SessieKetenTest {

    // Uniek per test: de Redis-container wordt gedeeld tussen de tests van dit profiel, en de grens per ontvanger telt mee.
    private val ontvanger = "OIN:" + teller.incrementAndGet().toString().padStart(20, '0').takeLast(20)

    @Test
    fun `verlengen houdt de sessie langer dan de inactiviteit in leven`() {
        val sessie = start()

        Thread.sleep(1_200)
        verleng(sessie).then().statusCode(200)
        Thread.sleep(1_200)

        // Zonder verlenging was de sessie na 2 s verlopen.
        lees(sessie).then().statusCode(200)
    }

    @Test
    fun `opvragen verlengt niet, dus een alleen opgevraagde sessie verloopt`() {
        val sessie = start()

        Thread.sleep(1_200)
        lees(sessie).then().statusCode(200)
        Thread.sleep(1_000)

        lees(sessie).then().statusCode(401)
        verleng(sessie).then().statusCode(401)
    }

    @Test
    fun `een plafond van het portaal laat de sessie dan eindigen, en verlengen gaat er niet voorbij`() {
        val plafond = Instant.now().plusMillis(1_500)
        val sessie = start("""{"uiterlijkGeldigTot":"$plafond"}""")

        Thread.sleep(700)
        val verlengd = verleng(sessie).then().statusCode(200).extract()

        val uiterlijk = Instant.parse(verlengd.path("uiterlijkGeldigTot"))
        assertEquals(plafond.toEpochMilli(), uiterlijk.toEpochMilli())
        assertTrue(!Instant.parse(verlengd.path<String>("geldigTot")).isAfter(uiterlijk), "verlengd voorbij het plafond")

        Thread.sleep(1_000)

        lees(sessie).then().statusCode(401)
    }

    @Test
    fun `een beeindigde sessie is niet meer te verlengen`() {
        val sessie = start()

        given().header("X-Ontvanger", ontvanger).header("X-Sessie", sessie).delete("/api/v1/sessies/huidige")
            .then().statusCode(204)

        verleng(sessie).then().statusCode(401).header("WWW-Authenticate", "X-Sessie realm=\"berichtenuitvraag\"")
    }

    private fun start(body: String? = null): String {
        // Zonder body toch JSON: REST Assured stuurt anders een formulier-mediatype mee, en dat is 415.
        val verzoek = given().header("X-Ontvanger", ontvanger).contentType("application/json")

        body?.let { verzoek.body(it) }

        return verzoek.post("/api/v1/sessies").then().statusCode(201).extract().path("sessieId")
    }

    private fun lees(sessie: String): Response =
        given().header("X-Ontvanger", ontvanger).header("X-Sessie", sessie).get("/api/v1/sessies/huidige")

    private fun verleng(sessie: String): Response =
        given().header("X-Ontvanger", ontvanger).header("X-Sessie", sessie).post("/api/v1/sessies/huidige/_verlengen")

    private companion object {
        val teller = AtomicLong(System.nanoTime())
    }
}

/** Echte facade en Redis (Redis Stack voor de index-bootstrap), met sessieduren van seconden. */
class KorteSessieKetenProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
        "berichtensessiecache.sessie.inactiviteit" to "PT2S",
        "berichtensessiecache.sessie.max-duur" to "PT1M",
    )
}
