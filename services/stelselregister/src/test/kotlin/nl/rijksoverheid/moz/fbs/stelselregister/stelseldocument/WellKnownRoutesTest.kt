package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.jose4j.jwk.EllipticCurveJsonWebKey
import org.jose4j.jwk.JsonWebKeySet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock

@QuarkusTest
class WellKnownRoutesTest {

    private val sleutelset = "/.well-known/jwks.json"

    @Test
    fun `de sleutelset is een JWK Set met zijn eigen mediatype en een langere cache dan het document`() {
        given()
            .`when`().get(sleutelset)
            .then()
            .statusCode(200)
            .contentType("application/jwk-set+json")
            .header("Cache-Control", "public, max-age=3600")
            .header("API-Version", nullValue())
    }

    // RFC 7517: `keys` is een array van JWK's; voor EC zijn `kty`, `crv`, `x` en `y` verplicht.
    // jose4j leest de set zoals een afnemer dat zou doen en weigert een ongeldige sleutel.
    @Test
    fun `de sleutelset bevat de publieke sleutel waarmee het document is ondertekend, en niets privés`() {
        val ruw = given().get(sleutelset).then().extract().asString()
        val header = Afnemer.header(given().get("/api/v1/stelseldocument").then().extract().asString())

        val sleutel = JsonWebKeySet(ruw).jsonWebKeys.single() as EllipticCurveJsonWebKey

        assertEquals(header.path("kid").asText(), sleutel.keyId)
        assertEquals("P-256", sleutel.curveName)
        assertEquals("sig", sleutel.use)
        assertEquals("ES256", sleutel.algorithm)
        assertEquals(Afnemer.certificaat(header.path("x5c")[0].asText()).publicKey, sleutel.publicKey)
        assertNull(sleutel.privateKey)
        assertFalse(Afnemer.JSON.readTree(ruw).path("keys")[0].has("d"))
    }

    @Test
    fun `If-None-Match op de sleutelset geeft 304`() {
        val etag = given().get(sleutelset).then().extract().header("ETag")

        given()
            .header("If-None-Match", etag)
            .`when`().get(sleutelset)
            .then()
            .statusCode(304)
            .header("ETag", etag)
            .body(equalTo(""))
    }

    @Test
    fun `security-txt verwijst door naar het centrale bestand`() {
        given()
            .redirects().follow(false)
            .`when`().get("/.well-known/security.txt")
            .then()
            .statusCode(302)
            .header("Location", "https://www.ncsc.nl/.well-known/security.txt")
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://www.ncsc.nl/.well-known/security.txt", "/.well-known/security.txt", "https:///pad", "ftp://x.example/s"])
    fun `een doel voor security-txt dat geen absolute https-URL is blokkeert de start`(doel: String) {
        val bron = Sleutelbron(
            object : StelseldocumentConfig {
                override fun uitgeverOin() = error("niet gebruikt")
                override fun omgeving() = error("niet gebruikt")
                override fun geldigheid() = error("niet gebruikt")
                override fun verversen() = error("niet gebruikt")
                override fun keystore() = error("niet gebruikt")
            },
            Clock.systemUTC(),
        )

        assertThrows(IllegalArgumentException::class.java) { WellKnownRoutes(bron, doel) }
    }
}
