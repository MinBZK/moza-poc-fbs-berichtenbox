package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import com.fasterxml.jackson.databind.ObjectMapper
import nl.rijksoverheid.moz.fbs.kern.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

class AppManagerTest {

    private val json = ObjectMapper()
    private val b64url = Base64.getUrlEncoder().withoutPadding()
    private val geldigTot = Instant.parse("2030-01-01T00:00:00Z")

    private fun coordinaat(waarde: BigInteger): String {
        val bytes = waarde.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()

        return b64url.encodeToString(ByteArray(32 - bytes.size) + bytes)
    }

    private fun sleutel(kid: String = "k1"): AppManagerSleutel {
        val punt = (KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }.public as ECPublicKey).w

        return AppManagerSleutel(kid, coordinaat(punt.affineX), coordinaat(punt.affineY), geldigTot)
    }

    private fun appManager(oin: String = "00000000000000001100", vararg sleutels: AppManagerSleutel = arrayOf(sleutel())) =
        AppManager(oin, "Demo-App Manager", sleutels.toList())

    private fun payload(document: Stelseldocument) =
        json.readTree(document.payload("00000000000000001000", "test", Instant.EPOCH, Instant.EPOCH.plusSeconds(60)))

    @Test
    fun `een App Manager staat met zijn sleutels als JWK in het document`() {
        val sleutel = sleutel("app-manager-1")
        val appManager = payload(Stelseldocument(emptyList(), listOf(appManager(sleutels = arrayOf(sleutel))))).path("app_managers")[0]

        assertEquals(listOf("oin", "name", "public_keys"), appManager.fieldNames().asSequence().toList())
        assertEquals("00000000000000001100", appManager.path("oin").asText())

        val jwk = appManager.path("public_keys")[0]

        assertEquals("app-manager-1", jwk.path("kid").asText())
        assertEquals(geldigTot.epochSecond, jwk.path("exp").asLong())
        assertEquals(
            mapOf("kty" to "EC", "crv" to "P-256", "x" to sleutel.x, "y" to sleutel.y),
            jwk.path("jwk").properties().associate { it.key to it.value.asText() },
        )
    }

    @ParameterizedTest(name = "{0} App Managers")
    @ValueSource(ints = [0, 1, 3])
    fun `App Managers staan op OIN en hun sleutels op kid gesorteerd`(aantal: Int) {
        val appManagers = (aantal downTo 1).map { appManager("%020d".format(it), sleutel("b"), sleutel("a")) }

        val lijst = payload(Stelseldocument(emptyList(), appManagers)).path("app_managers")

        assertEquals((1..aantal).map { "%020d".format(it) }, lijst.map { it.path("oin").asText() })
        lijst.forEach { assertEquals(listOf("a", "b"), it.path("public_keys").map { sleutel -> sleutel.path("kid").asText() }) }
    }

    @Test
    fun `een andere App Manager of sleutel geeft een andere versie`() {
        val sleutel = sleutel()
        val basis = Stelseldocument(emptyList(), listOf(appManager(sleutels = arrayOf(sleutel)))).versie

        val varianten = listOf(
            Stelseldocument(emptyList()),
            Stelseldocument(emptyList(), listOf(appManager(sleutels = arrayOf(sleutel("k2"), sleutel)))),
            Stelseldocument(emptyList(), listOf(appManager(sleutels = arrayOf(AppManagerSleutel(sleutel.kid, sleutel.x, sleutel.y, geldigTot.plusSeconds(1)))))),
            Stelseldocument(emptyList(), listOf(appManager("00000000000000001200", sleutel))),
            Stelseldocument(emptyList(), listOf(AppManager("00000000000000001100", "Andere naam", listOf(sleutel)))),
            Stelseldocument(emptyList(), listOf(appManager(sleutels = arrayOf(sleutel("x").let { AppManagerSleutel(sleutel.kid, it.x, it.y, geldigTot) })))),
        ).map { it.versie }

        varianten.forEach { assertNotEquals(basis, it) }
        assertEquals(varianten.size, varianten.toSet().size)
    }

    @Test
    fun `een App Manager mag niet dubbel in het document staan`() {
        assertThrows(IllegalArgumentException::class.java) { Stelseldocument(emptyList(), listOf(appManager(), appManager())) }
    }

    @Test
    fun `een App Manager zonder geldige OIN, naam, sleutel of met een dubbele kid wordt geweigerd`() {
        assertThrows(DomainValidationException::class.java) { appManager("123") }
        assertThrows(IllegalArgumentException::class.java) { AppManager("00000000000000001100", " ", listOf(sleutel())) }
        assertThrows(IllegalArgumentException::class.java) { AppManager("00000000000000001100", "Naam", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { appManager(sleutels = arrayOf(sleutel("k"), sleutel("k"))) }
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = [31, 33])
    fun `een coördinaat die niet precies 32 bytes is, wordt geweigerd, voor x en voor y`(lengte: Int) {
        val goed = sleutel()
        val fout = b64url.encodeToString(ByteArray(lengte) { 1 })

        assertThrows(IllegalArgumentException::class.java) { AppManagerSleutel("k", fout, goed.y, geldigTot) }
        assertThrows(IllegalArgumentException::class.java) { AppManagerSleutel("k", goed.x, fout, geldigTot) }
    }

    @Test
    fun `een sleutel naast de curve, met opvulling, zonder kid of in ongeldige base64 wordt geweigerd`() {
        val goed = sleutel()
        val anderY = coordinaat(BigInteger(1, Base64.getUrlDecoder().decode(goed.y)).add(BigInteger.ONE))

        assertThrows(IllegalArgumentException::class.java) { AppManagerSleutel("k", goed.x, anderY, geldigTot) }
        assertThrows(IllegalArgumentException::class.java) { AppManagerSleutel("k", goed.x + "=", goed.y, geldigTot) }
        assertThrows(IllegalArgumentException::class.java) { AppManagerSleutel(" ", goed.x, goed.y, geldigTot) }
        assertThrows(IllegalArgumentException::class.java) { AppManagerSleutel("k", "!!!", goed.y, geldigTot) }
    }

    /** (0, √b) ligt op P-256; met x + p ligt hij er modulo p nog steeds op, en weigert alleen de veldcontrole hem. */
    @Test
    fun `een coördinaat gelijk aan of groter dan p ligt buiten het veld`() {
        val p = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
        val y0 = BigInteger("66485c780e2f83d72433bd5d84a06bb6541c2af31dae871728bf856a174f93f4", 16)

        AppManagerSleutel("k", coordinaat(BigInteger.ZERO), coordinaat(y0), geldigTot)

        assertThrows(IllegalArgumentException::class.java) { AppManagerSleutel("k", coordinaat(p), coordinaat(y0), geldigTot) }
    }

    /**
     * Het laatste teken van 32 bytes in base64url draagt twee ongebruikte bits. Het teken direct
     * erna in het alfabet geeft dezelfde bytes, maar is niet de canonieke schrijfwijze.
     */
    @Test
    fun `een niet-canonieke schrijfwijze van dezelfde bytes wordt geweigerd`() {
        val alfabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val goed = sleutel()
        val laatste = alfabet.indexOf(goed.x.last())
        val zelfdeBytes = goed.x.dropLast(1) + alfabet[laatste + 1]

        assertEquals(0, laatste % 4)
        assertTrue(Base64.getUrlDecoder().decode(zelfdeBytes).contentEquals(Base64.getUrlDecoder().decode(goed.x)))
        assertThrows(IllegalArgumentException::class.java) { AppManagerSleutel("k", zelfdeBytes, goed.y, geldigTot) }
    }
}
