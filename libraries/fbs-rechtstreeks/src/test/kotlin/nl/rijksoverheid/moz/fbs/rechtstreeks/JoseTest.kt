package nl.rijksoverheid.moz.fbs.rechtstreeks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

class JoseTest {

    private fun getal(b64url: String) = BigInteger(1, Base64.getUrlDecoder().decode(b64url))

    /**
     * De ECDH-ES-vector uit RFC 7518, bijlage C. Toetst de Concat KDF van [TestApp] tegen een
     * bekende uitkomst, zodat de tests niet op een fout in hun eigen app-kant leunen.
     */
    @Test
    fun `de Concat KDF van de testapp geeft de sleutel uit RFC 7518 bijlage C`() {
        val sleutels = KeyFactory.getInstance("EC")
        val bob = sleutels.generatePrivate(ECPrivateKeySpec(getal("VEmDZpDXXK8p8N0Cndsxs924q6nS1RXFASRl6BfUqdw"), Jose.P256))
        val aliceEpk = sleutels.generatePublic(
            ECPublicKeySpec(ECPoint(getal("gI0GAILBdu7T53akrFmMyGcsF3n5dO7MmwNBHKW5SV0"), getal("SLW_xSffzlPWrHEVI30DHM_4egVwt3NQqeUD7nMFpps")), Jose.P256),
        )

        val z = KeyAgreement.getInstance("ECDH").run {
            init(bob)
            doPhase(aliceEpk, true)
            generateSecret()
        }

        val sleutel = TestApp.concatKdf(z, "A128GCM", 128, "Alice".toByteArray(), "Bob".toByteArray())

        assertEquals("VqqN6vgjbSBcIijNcacQGg", TestApp.b64url.encodeToString(sleutel))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["x negatief", "x gelijk aan p", "y negatief", "y gelijk aan p", "naast de curve"])
    fun `een punt buiten P-256 ligt niet op de curve`(geval: String) {
        val echt = TestApp.sleutelpaar().public as ECPublicKey
        val p = (Jose.P256.curve.field as ECFieldFp).p

        val punt = when (geval) {
            "x negatief" -> ECPoint(BigInteger.ONE.negate(), echt.w.affineY)
            "x gelijk aan p" -> ECPoint(p, echt.w.affineY)
            "y negatief" -> ECPoint(echt.w.affineX, BigInteger.ONE.negate())
            "y gelijk aan p" -> ECPoint(echt.w.affineX, p)
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
        assertFalse(Jose.opCurve(sleutel))
    }
}
