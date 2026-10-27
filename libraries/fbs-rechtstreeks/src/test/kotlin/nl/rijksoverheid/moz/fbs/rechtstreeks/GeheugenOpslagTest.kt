package nl.rijksoverheid.moz.fbs.rechtstreeks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.interfaces.ECPrivateKey
import java.time.Duration

class GeheugenOpslagTest {

    private val opzet = Opzet()
    private val sleutel = TestApp.sleutelpaar().private as ECPrivateKey

    private fun sessie(verifier: String, token: String, geldig: Duration = Duration.ofMinutes(15)) =
        Sessie(verifier, token, TestApp.aesSleutel(), Identiteit.AppSleutel(opzet.app.public as java.security.interfaces.ECPublicKey, "tp"), opzet.nu.plus(geldig))

    @Test
    fun `een challenge is één keer op te halen`() {
        val opslag = GeheugenOpslag(opzet.klok)

        opslag.bewaar("m", "n", "c", sleutel, opzet.nu.plusSeconds(60))

        assertEquals(sleutel, opslag.neem("m", "n"))
        assertNull(opslag.neem("m", "n"))
    }

    @Test
    fun `een verlopen challenge is niet meer op te halen`() {
        val opslag = GeheugenOpslag(opzet.klok)

        opslag.bewaar("m", "n", "c", sleutel, opzet.nu.plusSeconds(60))
        opzet.nu = opzet.nu.plusSeconds(60)

        assertNull(opslag.neem("m", "n"))
    }

    @ParameterizedTest(name = "quotum {0}")
    @ValueSource(ints = [1, 3])
    fun `het quotum per client raakt alleen die client`(quotum: Int) {
        val opslag = GeheugenOpslag(opzet.klok, maxChallengesPerClient = quotum)

        repeat(quotum) { opslag.bewaar("m", "n$it", "veelvraat", sleutel, opzet.nu.plusSeconds(60L - it)) }

        val fout = assertThrows<OpslagVolException> { opslag.bewaar("m", "x", "veelvraat", sleutel, opzet.nu.plusSeconds(60)) }

        // De eerste challenge van díe client komt vrij na 60 − (quotum − 1) seconden.
        assertEquals(Duration.ofSeconds(60L - (quotum - 1)), fout.opnieuwNa)

        opslag.bewaar("m", "y", "ander", sleutel, opzet.nu.plusSeconds(60))
    }

    @Test
    fun `het totaal is begrensd, en verlopen challenges maken weer ruimte`() {
        val opslag = GeheugenOpslag(opzet.klok, maxChallenges = 2)

        opslag.bewaar("m", "a", "c1", sleutel, opzet.nu.plusSeconds(10))
        opslag.bewaar("m", "b", "c2", sleutel, opzet.nu.plusSeconds(60))

        val fout = assertThrows<OpslagVolException> { opslag.bewaar("m", "c", "c3", sleutel, opzet.nu.plusSeconds(60)) }

        assertEquals(Duration.ofSeconds(10), fout.opnieuwNa)

        opzet.nu = opzet.nu.plusSeconds(10)
        opslag.bewaar("m", "c", "c3", sleutel, opzet.nu.plusSeconds(60))

        assertNotNull(opslag.neem("m", "c"))
    }

    @Test
    fun `dezelfde nonce bij twee Verifiers zijn twee challenges`() {
        val opslag = GeheugenOpslag(opzet.klok)
        val ander = TestApp.sleutelpaar().private as ECPrivateKey

        opslag.bewaar("m1", "n", "c", sleutel, opzet.nu.plusSeconds(60))
        opslag.bewaar("m2", "n", "c", ander, opzet.nu.plusSeconds(60))

        assertEquals(sleutel, opslag.neem("m1", "n"))
        assertEquals(ander, opslag.neem("m2", "n"))
    }

    @Test
    fun `sessies zijn per Verifier, verlopen, en begrensd`() {
        val opslag = GeheugenOpslag(opzet.klok, maxSessies = 1)

        opslag.bewaar(sessie("m1", "t", Duration.ofSeconds(30)))

        assertNotNull(opslag.zoek("m1", "t"))
        assertNull(opslag.zoek("m2", "t"))

        val fout = assertThrows<OpslagVolException> { opslag.bewaar(sessie("m1", "u")) }

        assertEquals(Duration.ofSeconds(30), fout.opnieuwNa)

        opzet.nu = opzet.nu.plusSeconds(30)

        assertNull(opslag.zoek("m1", "t"))

        opslag.bewaar(sessie("m1", "u"))

        assertNotNull(opslag.zoek("m1", "u"))
    }

    @Test
    fun `een volle opslag geeft bij maakChallenge een OpslagVolException`() {
        val opslag = GeheugenOpslag(opzet.klok, maxChallengesPerClient = 1)
        val verifier = Verifier(
            Opzet.MAGAZIJN_OIN,
            VerifierSleutel(Opzet.MAGAZIJN_KID, opzet.magazijnSleutel.private as ECPrivateKey),
            UitgeverSleutels.GEEN,
            Opzet.SCOPE,
            opslag,
            opslag,
            opzet.klok,
        )

        verifier.maakChallenge("c")

        assertThrows<OpslagVolException> { verifier.maakChallenge("c") }
    }
}
