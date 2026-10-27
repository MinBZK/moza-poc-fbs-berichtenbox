package nl.rijksoverheid.moz.fbs.rechtstreeks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.interfaces.ECPublicKey
import java.time.Instant

class SleutelsTest {

    private val geldigTot = Instant.parse("2027-01-01T00:00:00Z")

    @Test
    fun `een App Manager-sleutel uit coordinaten is dezelfde public key`() {
        val paar = TestApp.sleutelpaar()
        val jwk = TestApp.jwk(paar.public as ECPublicKey)
        val sleutel = AppManagerSleutel.uitCoordinaten(jwk["x"] as String, jwk["y"] as String, geldigTot)

        assertEquals((paar.public as ECPublicKey).w, sleutel.publicKey.w)
        assertEquals(geldigTot, sleutel.geldigTot)
    }

    @Test
    fun `coordinaten van de verkeerde lengte, buiten de curve of onleesbaar worden geweigerd`() {
        val jwk = TestApp.jwk(TestApp.sleutelpaar().public as ECPublicKey)
        val x = jwk["x"] as String
        val y = jwk["y"] as String
        val anderY = TestApp.jwk(TestApp.sleutelpaar().public as ECPublicKey)["y"] as String

        listOf(x to anderY, x.drop(2) to y, "" to y, "!!!" to y, x to "${y}AA").forEach { (a, b) ->
            assertThrows(IllegalArgumentException::class.java) { AppManagerSleutel.uitCoordinaten(a, b, geldigTot) }
        }
    }
}
