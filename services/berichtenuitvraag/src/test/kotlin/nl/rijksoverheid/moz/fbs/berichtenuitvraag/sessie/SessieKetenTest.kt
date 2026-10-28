package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Het verlopen van een sessie, door de hele keten: HTTP, filter, facade en een echte Redis.
 * Inactiviteit 4 s en een maximale duur van een minuut uit [KorteSessieKetenProfile], zodat verlopen
 * binnen een test te zien is. Elke controle dat een sessie nog loopt, heeft minstens anderhalve
 * seconde speling. Zonder ophaalronde antwoordt `GET /berichten` met een 409 als de sessie loopt, en
 * met een 401 als ze verlopen is.
 */
@QuarkusTest
@TestProfile(KorteSessieKetenProfile::class)
class SessieKetenTest {

    // Uniek per test: de Redis-container wordt gedeeld tussen de tests van dit profiel.
    private val ontvanger = "OIN:" + teller.incrementAndGet().toString().padStart(20, '0').takeLast(20)

    @Test
    fun `een verzoek houdt de sessie langer dan de inactiviteit in leven`() {
        val sessie = start()

        Thread.sleep(2_000)
        berichten(sessie).then().statusCode(409)
        Thread.sleep(2_500)

        // Zonder verlenging was de sessie na 4 s verlopen.
        berichten(sessie).then().statusCode(409)
    }

    @Test
    fun `zonder verzoek verloopt de sessie na de inactiviteit`() {
        val sessie = start()

        Thread.sleep(4_300)

        berichten(sessie).then().statusCode(401)
    }

    @Test
    fun `een beeindigde sessie is niet meer te verlengen`() {
        val sessie = start()

        given().header("X-Ontvanger", ontvanger).header("X-Sessie", sessie).delete("/api/v1/sessies/huidige")
            .then().statusCode(204)

        berichten(sessie).then().statusCode(401).header("WWW-Authenticate", "X-Sessie realm=\"berichtenuitvraag\"")
    }

    private fun start(): String =
        given().header("X-Ontvanger", ontvanger).contentType("application/json").post("/api/v1/sessies")
            .then().statusCode(201).extract().path("sessieId")

    private fun berichten(sessie: String): Response =
        given().header("X-Ontvanger", ontvanger).header("X-Sessie", sessie).get("/api/v1/berichten")

    private companion object {
        val teller = AtomicLong(System.nanoTime())
    }
}

/** Echte facade en Redis (Redis Stack voor de index-bootstrap), met sessieduren van seconden. */
class KorteSessieKetenProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
        "berichtensessiecache.sessie.inactiviteit" to "PT4S",
        "berichtensessiecache.sessie.max-duur" to "PT1M",
    )
}
