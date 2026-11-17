package nl.rijksoverheid.moz.fbs.rechtstreeks

import org.jose4j.json.JsonUtil
import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType
import org.jose4j.jwe.ContentEncryptionAlgorithmIdentifiers
import org.jose4j.jwe.JsonWebEncryption
import org.jose4j.jwe.KeyManagementAlgorithmIdentifiers
import org.jose4j.jwk.EllipticCurveJsonWebKey
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwx.JsonWebStructure
import org.jose4j.keys.AesKey
import org.jose4j.keys.EllipticCurves
import org.jose4j.lang.JoseException
import java.security.Key
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.util.Base64

/**
 * De JOSE-regels van de standaard (3.1, 3.4), op één plek: per soort bericht staan algoritme en
 * toegestane headervelden vast. Een header met een ander veld wordt geweigerd, dus ook elke manier
 * om een verificatiesleutel of certificaat mee te sturen (`jwk`, `jku`, `x5u`, `x5c`) en `crit`;
 * alleen de `epk` van `ECDH-ES` mag. Elke JWE draagt `typ` `JWE` (API Design Rules,
 * `/encryption/jwe`). Wat aan de header van een JWE niet klopt, is een [JweOngeldigException];
 * wat aan een JWS of aan de `epk` niet klopt, een [AfgewezenException].
 *
 * jose4j cast headerwaarden zonder controle naar het verwachte type. Een getal waar tekst hoort,
 * zou daarom als `ClassCastException` ontsnappen en bij de aanroeper een `500` worden in plaats
 * van een `400` of `401`. Daarom staat ook het type van elke headerwaarde vast, en mag `epk` alleen
 * `kty`, `crv`, `x` en `y` bevatten: jose4j leest de `epk` bij de key agreement opnieuw in, en
 * een extra lid zoals `x5c` kan dan nog een andere exception opleveren.
 */
internal object Jose {

    val P256: ECParameterSpec = EllipticCurves.P256

    private const val P256_BYTES = 32
    private const val TYP_JWE = "JWE"

    private val HEADER_JWS = setOf("alg", "typ", "kid")
    private val HEADER_JWE_ECDH = setOf("alg", "enc", "epk", "apu", "apv", "kid", "typ", "cty")
    private val HEADER_JWE_DIR = setOf("alg", "enc", "kid", "typ", "cty")

    /** De leden van een publieke EC-JWK die tellen; in `cnf.jwk` negeren we de rest (`alg`, `use`, `key_ops`, `ext`). */
    private val JWK_LEDEN = listOf("kty", "crv", "x", "y")

    private val ALLEEN_ES256 = AlgorithmConstraints(ConstraintType.PERMIT, AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256)
    private val ALLEEN_A256GCM = AlgorithmConstraints(ConstraintType.PERMIT, ContentEncryptionAlgorithmIdentifiers.AES_256_GCM)

    /** Tekent [claims] als JWS (compact, ES256). */
    fun teken(claims: Map<String, Any>, sleutel: VerifierSleutel): String = JsonWebSignature().run {
        payload = JsonUtil.toJson(claims)
        algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
        setHeader("typ", "jwt")
        keyIdHeaderValue = sleutel.kid
        key = sleutel.privateKey
        compactSerialization
    }

    /** Een JWS die nog niet geverifieerd is: header gecontroleerd, payload ongelezen. */
    fun leesJws(compact: String, reden: Afwijzing): JsonWebSignature {
        val jws = try {
            JsonWebSignature().apply { compactSerialization = compact }
        } catch (e: JoseException) {
            throw afgewezen(reden, e)
        }

        val header = header(jws) ?: throw AfgewezenException(reden)

        if (!headerKlopt(header, HEADER_JWS)) throw AfgewezenException(Afwijzing.HEADER_NIET_TOEGESTAAN)

        jws.setAlgorithmConstraints(ALLEEN_ES256)

        return jws
    }

    /** Controleert de handtekening van [jws] met [sleutel] en geeft de payload als JSON-object. */
    fun verifieer(jws: JsonWebSignature, sleutel: ECPublicKey, reden: Afwijzing): Map<String, Any> {
        jws.key = sleutel

        val klopt = try {
            jws.verifySignature()
        } catch (e: JoseException) {
            throw afgewezen(reden, e)
        } catch (e: ClassCastException) {
            // Vangnet naast de typecontrole op header en epk: jose4j cast waarden zonder controle.
            throw afgewezen(reden, e)
        }

        if (!klopt) throw AfgewezenException(reden)

        return jsonObject(jws.unverifiedPayload, reden)
    }

    /** Ontsleutelt een JWE met `ECDH-ES` en `A256GCM` met de private [tijdelijkeSleutel] van de challenge. */
    fun ontsleutelEcdh(compact: String, tijdelijkeSleutel: Key): String {
        val jwe = leesJwe(compact, HEADER_JWE_ECDH, KeyManagementAlgorithmIdentifiers.ECDH_ES, Afwijzing.PRESENTATIE_ONLEESBAAR)
        val epk = jwe.headers.getObjectHeaderValue("epk") as? Map<*, *>
            ?: throw AfgewezenException(Afwijzing.TIJDELIJKE_SLEUTEL_ONGELDIG)

        if (!JWK_LEDEN.containsAll(epk.keys)) throw AfgewezenException(Afwijzing.TIJDELIJKE_SLEUTEL_ONGELDIG)

        // Alleen de controle telt: een epk naast P-256 wordt geweigerd vóór de key agreement, zodat
        // een invalid-curve-aanval niet van de JOSE-library afhangt.
        ecSleutel(epk, Afwijzing.TIJDELIJKE_SLEUTEL_ONGELDIG)

        return plaintext(jwe, tijdelijkeSleutel, Afwijzing.PRESENTATIE_ONLEESBAAR)
    }

    /** Ontsleutelt een JWE met `dir` en `A256GCM` onder de sessiesleutel. */
    fun ontsleutelDir(compact: String, aesSleutel: ByteArray): String {
        val jwe = leesJwe(compact, HEADER_JWE_DIR, KeyManagementAlgorithmIdentifiers.DIRECT, Afwijzing.VERSLEUTELING_ONGELDIG)

        return plaintext(jwe, AesKey(aesSleutel), Afwijzing.VERSLEUTELING_ONGELDIG)
    }

    /** Versleutelt [payload] als JWE met `dir` en `A256GCM` onder de sessiesleutel. */
    fun versleutelDir(payload: String, aesSleutel: ByteArray): String = JsonWebEncryption().run {
        setPlaintext(payload)
        algorithmHeaderValue = KeyManagementAlgorithmIdentifiers.DIRECT
        encryptionMethodHeaderParameter = ContentEncryptionAlgorithmIdentifiers.AES_256_GCM
        setHeader("typ", TYP_JWE)
        key = AesKey(aesSleutel)
        compactSerialization
    }

    /**
     * Leest een publieke EC-sleutel uit een JWK: alleen P-256, zonder private deel, en met een
     * punt dat op de curve ligt. Een punt ernaast kan bij ECDH een deel van de private key van de
     * ontvanger prijsgeven (invalid-curve-aanval). Alleen [JWK_LEDEN] gaan naar jose4j, als tekst.
     */
    fun ecSleutel(jwk: Map<*, *>, reden: Afwijzing): ECPublicKey {
        if (jwk.containsKey("d")) throw AfgewezenException(reden)

        val leden = JWK_LEDEN.associateWith { jwk[it] as? String ?: throw AfgewezenException(reden) }

        // RFC 7518 §6.2.1.2: x en y hebben precies de veldlengte. Een groter getal laat de JDK een
        // kale RuntimeException gooien in plaats van een JoseException.
        if (!coordinaat(leden.getValue("x")) || !coordinaat(leden.getValue("y"))) throw AfgewezenException(reden)

        val sleutel = try {
            JsonWebKey.Factory.newJwk(leden)
        } catch (e: JoseException) {
            throw afgewezen(reden, e)
        }

        val ec = sleutel as? EllipticCurveJsonWebKey ?: throw AfgewezenException(reden)

        if (ec.curveName != EllipticCurves.P_256 || !opCurve(ec.ecPublicKey)) throw AfgewezenException(reden)

        return ec.ecPublicKey
    }

    private fun coordinaat(waarde: String): Boolean = try {
        Base64.getUrlDecoder().decode(waarde).size == P256_BYTES
    } catch (ignored: IllegalArgumentException) {
        false
    }

    /** RFC 7638-thumbprint (SHA-256, base64url) van een publieke EC-sleutel. */
    fun thumbprint(sleutel: ECPublicKey): String =
        EllipticCurveJsonWebKey(sleutel).calculateBase64urlEncodedThumbprint("SHA-256")

    fun jsonObject(tekst: String, reden: Afwijzing): Map<String, Any> = try {
        JsonUtil.parseJson(tekst)
    } catch (e: JoseException) {
        throw afgewezen(reden, e)
    }

    internal fun opCurve(sleutel: ECPublicKey): Boolean {
        val curve = P256.curve
        val p = (curve.field as ECFieldFp).p
        val x = sleutel.w.affineX
        val y = sleutel.w.affineY

        if (x == null || y == null || x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false

        return y.pow(2).mod(p) == (x.pow(3) + curve.a * x + curve.b).mod(p)
    }

    /**
     * Leest een JWE en controleert de header, die zonder sleutel te lezen is: alleen [toegestaan]
     * velden, [alg] met `A256GCM`, en `typ` `JWE`. De vergelijking van `typ` negeert hoofdletters,
     * zoals bij een mediatype (RFC 7515, 4.1.9). De constraints van jose4j blijven daarnaast staan.
     */
    private fun leesJwe(compact: String, toegestaan: Set<String>, alg: String, reden: Afwijzing): JsonWebEncryption {
        val jwe = try {
            JsonWebEncryption().apply { compactSerialization = compact }
        } catch (e: JoseException) {
            throw jweOngeldig(reden, e)
        }

        val header = header(jwe) ?: throw JweOngeldigException(reden)

        if (!headerKlopt(header, toegestaan)) throw JweOngeldigException(Afwijzing.HEADER_NIET_TOEGESTAAN)

        if (header["alg"] != alg || header["enc"] != ContentEncryptionAlgorithmIdentifiers.AES_256_GCM) {
            throw JweOngeldigException(Afwijzing.ALGORITME_ONJUIST)
        }

        if (!TYP_JWE.equals(header["typ"] as? String, ignoreCase = true)) throw JweOngeldigException(Afwijzing.TYP_ONJUIST)

        jwe.setAlgorithmConstraints(AlgorithmConstraints(ConstraintType.PERMIT, alg))
        jwe.setContentEncryptionAlgorithmConstraints(ALLEEN_A256GCM)

        return jwe
    }

    private fun plaintext(jwe: JsonWebEncryption, sleutel: Key, reden: Afwijzing): String {
        jwe.key = sleutel

        return try {
            jwe.plaintextString
        } catch (e: JoseException) {
            throw afgewezen(reden, e)
        } catch (e: ClassCastException) {
            // Vangnet naast de typecontrole op header en epk: jose4j cast waarden zonder controle.
            throw afgewezen(reden, e)
        }
    }

    /** De header als JSON-object; `null` als hij dat niet is. jose4j heeft hem dan al geweigerd, dus dit is een vangnet. */
    private fun header(structuur: JsonWebStructure): Map<String, Any>? = try {
        JsonUtil.parseJson(structuur.headers.fullHeaderAsJsonString)
    } catch (ignored: JoseException) {
        null
    }

    /** Alleen [toegestaan] velden, en elk met het type dat jose4j verwacht. */
    private fun headerKlopt(header: Map<String, Any>, toegestaan: Set<String>): Boolean =
        toegestaan.containsAll(header.keys) &&
            header.all { (naam, waarde) -> if (naam == "epk") waarde is Map<*, *> else waarde is String }
}
