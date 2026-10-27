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
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * De app-kant van het protocol, gebouwd op de JCA (`java.security`, `javax.crypto`) en niet op
 * jose4j. Zo toetsen de tests de Verifier tegen een tweede implementatie van JWS (ES256) en JWE
 * (`ECDH-ES` met Concat KDF, `dir`, `A256GCM`), en niet tegen de code die hij zelf gebruikt. Alleen
 * JSON gaat via jose4j.
 */
object TestApp {

    private val random = SecureRandom()
    val b64url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64urlDecoder = Base64.getUrlDecoder()

    fun sleutelpaar(curve: String = "secp256r1"): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec(curve))
        generateKeyPair()
    }

    fun jwk(sleutel: ECPublicKey, crv: String = "P-256", lengte: Int = 32): MutableMap<String, Any> = mutableMapOf(
        "kty" to "EC",
        "crv" to crv,
        "x" to b64url.encodeToString(coordinaat(sleutel.w.affineX, lengte)),
        "y" to b64url.encodeToString(coordinaat(sleutel.w.affineY, lengte)),
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

    /** Een JWS met `alg` `none`: zonder handtekening. */
    fun jwsZonderHandtekening(claims: Map<String, Any>): String =
        "${deel(JsonUtil.toJson(mapOf("alg" to "none")))}.${deel(JsonUtil.toJson(claims))}."

    /** Een JWS met `HS256` en [geheim] als HMAC-sleutel: de klassieke verwarring met een publieke sleutel. */
    fun jwsHs256(claims: Map<String, Any>, geheim: ByteArray, kid: String? = null): String {
        val header = mapOf("alg" to "HS256") + (kid?.let { mapOf("kid" to it) } ?: emptyMap())
        val invoer = "${deel(JsonUtil.toJson(header))}.${deel(JsonUtil.toJson(claims))}"

        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(geheim, "HmacSHA256"))
            doFinal(invoer.toByteArray(Charsets.US_ASCII))
        }

        return "$invoer.${b64url.encodeToString(mac)}"
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
     * JWE met `ECDH-ES` naar RFC 7518 §4.6, standaard met `A256GCM`. Met [alg] `ECDH-ES+A128KW`
     * wordt een inhoudssleutel ingepakt. [header] voegt velden toe of overschrijft ze; een eigen
     * `epk` daarin vervangt alleen de header, de sleutelafspraak blijft met het eigen tijdelijke paar.
     */
    fun jweEcdh(
        payload: String,
        ontvanger: ECPublicKey,
        header: Map<String, Any> = emptyMap(),
        alg: String = "ECDH-ES",
        enc: String = "A256GCM",
    ): String {
        val tijdelijk = sleutelpaar()

        val z = KeyAgreement.getInstance("ECDH").run {
            init(tijdelijk.private)
            doPhase(ontvanger, true)
            generateSecret()
        }

        val volledigeHeader = mapOf("alg" to alg, "enc" to enc, "epk" to jwk(tijdelijk.public as ECPublicKey)) + header
        val inhoudsbits = if (enc == "A128GCM") 128 else 256

        if (alg == "ECDH-ES+A128KW") {
            val kek = concatKdf(z, alg, 128)
            val inhoudssleutel = aesSleutel(inhoudsbits / 8)

            val ingepakt = Cipher.getInstance("AESWrap").run {
                init(Cipher.WRAP_MODE, SecretKeySpec(kek, "AES"))
                wrap(SecretKeySpec(inhoudssleutel, "AES"))
            }

            return aesGcm(deel(JsonUtil.toJson(volledigeHeader)), payload, inhoudssleutel, b64url.encodeToString(ingepakt))
        }

        return aesGcm(deel(JsonUtil.toJson(volledigeHeader)), payload, concatKdf(z, enc, inhoudsbits), versleuteldeSleutel = "")
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

    /** Concat KDF (NIST SP 800-56A) zoals RFC 7518 §4.6.2 hem invult, voor sleutels tot 256 bit. */
    fun concatKdf(z: ByteArray, algoritme: String, bits: Int, apu: ByteArray = ByteArray(0), apv: ByteArray = ByteArray(0)): ByteArray {
        val id = algoritme.toByteArray(Charsets.US_ASCII)

        val otherInfo = ByteBuffer.allocate(4 + id.size + 4 + apu.size + 4 + apv.size + 4)
            .putInt(id.size).put(id)
            .putInt(apu.size).put(apu)
            .putInt(apv.size).put(apv)
            .putInt(bits)
            .array()

        val digest = MessageDigest.getInstance("SHA-256").run {
            update(byteArrayOf(0, 0, 0, 1))
            update(z)
            update(otherInfo)
            digest()
        }

        return digest.copyOf(bits / 8)
    }

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

    private fun coordinaat(waarde: BigInteger, lengte: Int): ByteArray {
        val bytes = waarde.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()

        return ByteArray(lengte - bytes.size) + bytes
    }

    private fun deel(json: String): String = b64url.encodeToString(json.toByteArray())
}
