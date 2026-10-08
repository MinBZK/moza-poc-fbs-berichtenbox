package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * De hele keten zoals een app hem doorloopt: het document over HTTP ophalen en het accepteren
 * tegen een root die buiten de dienst om bekend is — hier uit de keystore die het profiel meegeeft.
 */
@QuarkusTest
@TestProfile(StelseldocumentKetenTest.MetKeystore::class)
class StelseldocumentKetenTest {

    class MetKeystore : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "stelseldocument.keystore.pad" to Testketens.geldig.pad.toString(),
            "stelseldocument.keystore.wachtwoord" to String(Testketens.geldig.wachtwoord),
        )
    }

    private val uitgever = "00000000000000001000"

    // Uit de config en niet uit Testketens: het profiel en deze test draaien in verschillende
    // classloaders en zouden elk hun eigen keten maken.
    private val root: X509Certificate by lazy {
        val config = ConfigProvider.getConfig()
        val wachtwoord = config.getValue("stelseldocument.keystore.wachtwoord", String::class.java).toCharArray()
        val pad = Path.of(config.getValue("stelseldocument.keystore.pad", String::class.java))
        val keystore = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(pad).use { load(it, wachtwoord) } }

        keystore.getCertificate(Wegwerpketen.ROOT_ALIAS) as X509Certificate
    }

    private fun document(): String = given().get("/api/v1/stelseldocument").then().statusCode(200).extract().asString()

    @Test
    fun `een app met de vastgelegde root accepteert het opgehaalde document`() {
        val payload = Afnemer(root, uitgever).accepteer(document(), Instant.now())

        assertEquals(2, payload.path("organizations").size())
    }

    @Test
    fun `de root zelf staat niet in x5c`() {
        val x5c = Afnemer.header(document()).path("x5c").map { Afnemer.certificaat(it.asText()) }

        assertEquals(1, x5c.size)
        assertEquals(false, x5c.contains(root))
        assertEquals(root.subjectX500Principal, x5c.single().issuerX500Principal)
    }

    @Test
    fun `een app met een andere root weigert het document`() {
        assertThrows(Afnemer.Geweigerd::class.java) {
            Afnemer(Testketens.ander.root, uitgever).accepteer(document(), Instant.now())
        }
    }

    @Test
    fun `een onderweg gewijzigd document wordt geweigerd`() {
        val delen = document().split('.')
        val vervalst = String(Base64.getUrlDecoder().decode(delen[1])).replace("localhost:8090", "aanvaller.example")
        val jws = listOf(delen[0], Base64.getUrlEncoder().withoutPadding().encodeToString(vervalst.toByteArray()), delen[2])
            .joinToString(".")

        assertThrows(Afnemer.Geweigerd::class.java) { Afnemer(root, uitgever).accepteer(jws, Instant.now()) }
    }

    @Test
    fun `een app weigert het document na de geldigheidsduur`() {
        val jws = document()

        assertThrows(Afnemer.Geweigerd::class.java) {
            Afnemer(root, uitgever).accepteer(jws, Instant.now().plus(Duration.ofHours(24)))
        }
    }

    @Test
    fun `de sleutelset noemt dezelfde sleutel als de header`() {
        val kid = Afnemer.header(document()).path("kid").asText()

        given().get("/.well-known/jwks.json").then().statusCode(200).body("keys[0].kid", org.hamcrest.Matchers.equalTo(kid))
    }
}
