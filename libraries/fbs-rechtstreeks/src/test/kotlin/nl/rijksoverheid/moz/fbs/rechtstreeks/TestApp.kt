package nl.rijksoverheid.moz.fbs.rechtstreeks

import org.jose4j.json.JsonUtil
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * De app-kant van het protocol, gebouwd op `java.security` en niet op jose4j. Zo toetsen de tests
 * de Verifier tegen een tweede implementatie van JWS (ES256) en JWE (`ECDH-ES` met Concat KDF,
 * `dir`, `A256GCM`), en niet tegen de code die hij zelf gebruikt. Alleen JSON gaat via jose4j.
 */
object TestApp {

    private val random = SecureRandom()
    private val b64url = Base64.getUrlEncoder().withoutPadding()
    private val b64urlDecoder = Base64.getUrlDecoder()

    fun sleutelpaar(curve: String = "secp256r1"): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec(curve))
        generateKeyPair()
    }

    fun jwk(sleutel: ECPublicKey): MutableMap<String, Any> = mutableMapOf(
        "kty" to "EC",
        "crv" to "P-256",
        "x" to b64url.encodeToString(coordinaat(sleutel.w.affineX)),
        "y" to b64url.encodeToString(coordinaat(sleutel.w.affineY)),
    )

    /** RFC 7638: de verplichte leden in lexicografische volgorde, zonder witruimte. */
    fun thumbprint(sleutel: ECPublicKey): String {
        val jwk = jwk(sleutel)
        val json = """{"crv":"P-256","kty":"EC","x":"${jwk["x"]}","y":"${jwk["y"]}"}"""

        return b64url.encodeToString(MessageDigest.getInstance("SHA-256").digest(json.toByteArray()))
    }

    fun jws(header: Map<String, Any>, claims: Map<String, Any>, sleutel: PrivateKey): String {
        val invoer = "${deel(JsonUtil.toJson(header))}.${deel(JsonUtil.toJson(claims))}"
        val handtekening = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(sleutel)
            update(invoer.toByteArray(Charsets.US_ASCII))
            sign()
        }

        return "$invoer.${b64url.encodeToString(handtekening)}"
    }

    fun es256(claims: Map<String, Any>, sleutel: PrivateKey, kid: String? = null): String =
        jws(mapOf("alg" to "ES256", "typ" to "jwt") + (kid?.let { mapOf("kid" to it) } ?: emptyMap()), claims, sleutel)

    fun verifieerJws(compact: String, sleutel: PublicKey): Map<String, Any> {
        val delen = compact.split('.')
        val klopt = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(sleutel)
            update("${delen[0]}.${delen[1]}".toByteArray(Charsets.US_ASCII))
            verify(b64urlDecoder.decode(delen[2]))
        }

        check(klopt) { "handtekening klopt niet" }

        return JsonUtil.parseJson(String(b64urlDecoder.decode(delen[1])))
    }

    fun header(compact: String): Map<String, Any> = JsonUtil.parseJson(String(b64urlDecoder.decode(compact.substringBefore('.'))))

    fun publicKeyUitDer(base64: String): ECPublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64))) as ECPublicKey

    /**
     * JWE met `ECDH-ES` (direct key agreement) en `A256GCM`, naar RFC 7518 §4.6. [extraHeader]
     * laat een test een headerveld toevoegen; [epk] een test een eigen tijdelijke sleutel opgeven.
     */
    fun jweEcdh(
        payload: String,
        ontvanger: ECPublicKey,
        extraHeader: Map<String, Any> = emptyMap(),
        epk: Map<String, Any>? = null,
    ): String {
        val tijdelijk = sleutelpaar()
        val z = KeyAgreement.getInstance("ECDH").run {
            init(tijdelijk.private)
            doPhase(ontvanger, true)
            generateSecret()
        }
        val header = mapOf("alg" to "ECDH-ES", "enc" to "A256GCM", "epk" to (epk ?: jwk(tijdelijk.public as ECPublicKey))) + extraHeader
        val sleutel = concatKdf(z, "A256GCM")

        return aesGcm(deel(JsonUtil.toJson(header)), payload, sleutel, versleuteldeSleutel = "")
    }

    fun jweDir(payload: String, sleutel: ByteArray): String =
        aesGcm(deel(JsonUtil.toJson(mapOf("alg" to "dir", "enc" to "A256GCM"))), payload, sleutel, versleuteldeSleutel = "")

    fun ontsleutelDir(compact: String, sleutel: ByteArray): String {
        val delen = compact.split('.')

        check(delen.size == 5 && delen[1].isEmpty()) { "geen JWE met dir" }
        check(header(compact) == mapOf("alg" to "dir", "enc" to "A256GCM")) { "onverwachte header" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(sleutel, "AES"), GCMParameterSpec(128, b64urlDecoder.decode(delen[2])))
            updateAAD(delen[0].toByteArray(Charsets.US_ASCII))
        }

        return String(cipher.doFinal(b64urlDecoder.decode(delen[3]) + b64urlDecoder.decode(delen[4])))
    }

    fun aesSleutel(bytes: Int = 32): ByteArray = ByteArray(bytes).also(random::nextBytes)

    private fun aesGcm(headerB64: String, payload: String, sleutel: ByteArray, versleuteldeSleutel: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val uitkomst = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(sleutel, "AES"), GCMParameterSpec(128, iv))
            updateAAD(headerB64.toByteArray(Charsets.US_ASCII))
            doFinal(payload.toByteArray())
        }
        val tekst = uitkomst.copyOfRange(0, uitkomst.size - 16)
        val tag = uitkomst.copyOfRange(uitkomst.size - 16, uitkomst.size)

        return listOf(headerB64, versleuteldeSleutel, b64url.encodeToString(iv), b64url.encodeToString(tekst), b64url.encodeToString(tag))
            .joinToString(".")
    }

    /** Concat KDF (NIST SP 800-56A) zoals RFC 7518 §4.6.2 hem invult, zonder `apu`/`apv`. */
    private fun concatKdf(z: ByteArray, enc: String): ByteArray {
        val algoritme = enc.toByteArray(Charsets.US_ASCII)
        val otherInfo = ByteBuffer.allocate(4 + algoritme.size + 4 + 4 + 4)
            .putInt(algoritme.size).put(algoritme)
            .putInt(0)
            .putInt(0)
            .putInt(256)
            .array()

        return MessageDigest.getInstance("SHA-256").run {
            update(byteArrayOf(0, 0, 0, 1))
            update(z)
            update(otherInfo)
            digest()
        }
    }

    private fun coordinaat(waarde: BigInteger): ByteArray {
        val bytes = waarde.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()

        return ByteArray(32 - bytes.size) + bytes
    }

    private fun deel(json: String): String = b64url.encodeToString(json.toByteArray())
}
