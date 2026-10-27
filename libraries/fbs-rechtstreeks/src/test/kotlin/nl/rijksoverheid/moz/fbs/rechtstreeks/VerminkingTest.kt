package nl.rijksoverheid.moz.fbs.rechtstreeks

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.math.BigInteger
import kotlin.random.Random

/**
 * Eigenschap: één verminkt teken in de presentatie of in het credential levert nooit een sessie
 * op, en altijd een [AfgewezenException]; nooit een andere exception die als `500` zou eindigen.
 */
class VerminkingTest {

    private fun verminkt(tekst: String, random: Random): String {
        // Niet het laatste teken van een base64url-deel: daarin zitten ongebruikte bits, dus een
        // ander teken kan dezelfde bytes opleveren en dan is er niets verminkt.
        val plekken = tekst.indices.filter { tekst[it] != '.' && it + 1 < tekst.length && tekst[it + 1] != '.' }
        val plek = plekken.random(random)
        val alfabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_."
        val vervanging = alfabet.filter { it != tekst[plek] }.random(random)

        return tekst.substring(0, plek) + vervanging + tekst.substring(plek + 1)
    }

    private fun verwachtAfwijzing(actie: () -> Unit) {
        try {
            actie()
            fail("verminkt verzoek leverde een sessie op")
        } catch (e: AfgewezenException) {
            assertTrue(e.reden in Afwijzing.entries)
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20])
    fun `een verminkte versleutelde presentatie wordt afgewezen`(seed: Long) {
        val opzet = Opzet()
        val verifier = opzet.verifier()
        val challenge = opzet.challenge(verifier)
        val versleuteld = opzet.versleuteld(challenge, opzet.presentatie(challenge))

        verwachtAfwijzing { verifier.voltooiMetCredential(challenge.nonce, verminkt(versleuteld, Random(seed))) }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20])
    fun `een verminkt credential of verminkte nonce-handtekening wordt afgewezen`(seed: Long) {
        val opzet = Opzet()
        val verifier = opzet.verifier()
        val challenge = opzet.challenge(verifier)
        val random = Random(seed)
        val presentatie = opzet.presentatie(challenge)
        val veld = if (random.nextBoolean()) "certificate" else "app_nonce_signature"

        presentatie[veld] = verminkt(presentatie[veld] as String, random)

        verwachtAfwijzing { verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, presentatie)) }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["negatief", "gelijk aan p", "naast de curve"])
    fun `een punt buiten P-256 ligt niet op de curve`(geval: String) {
        val echt = TestApp.sleutelpaar().public as ECPublicKey
        val p = (Jose.P256.curve.field as java.security.spec.ECFieldFp).p
        val punt = when (geval) {
            "negatief" -> ECPoint(BigInteger.ONE.negate(), echt.w.affineY)
            "gelijk aan p" -> ECPoint(p, echt.w.affineY)
            else -> ECPoint(echt.w.affineX, echt.w.affineY.add(BigInteger.ONE))
        }
        val sleutel = object : ECPublicKey {
            override fun getW() = punt

            override fun getParams() = Jose.P256

            override fun getAlgorithm() = "EC"

            override fun getFormat() = "X.509"

            override fun getEncoded() = ByteArray(0)
        }

        assertTrue(Jose.opCurve(echt))
        assertTrue(!Jose.opCurve(sleutel))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["secp384r1"])
    fun `een Verifier-sleutel buiten P-256 wordt geweigerd`(curve: String) {
        val sleutel = TestApp.sleutelpaar(curve).private as ECPrivateKey

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { VerifierSleutel("k", sleutel) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            VerifierSleutel(" ", TestApp.sleutelpaar().private as ECPrivateKey)
        }
    }
}
