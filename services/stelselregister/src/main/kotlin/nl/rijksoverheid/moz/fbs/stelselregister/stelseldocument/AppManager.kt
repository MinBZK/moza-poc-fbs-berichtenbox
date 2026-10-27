package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.time.Instant
import java.util.Base64

/**
 * Een publieke sleutel van een App Manager zoals het stelseldocument hem toont: een P-256-punt als
 * JWK-coördinaten, met de `kid` waaronder credentials hem aanwijzen en het moment waarop hij
 * verloopt (FSC voor Burgers 4.6). Een magazijn toetst hem op `iat` van het credential (4.6.2).
 */
class AppManagerSleutel(val kid: String, val x: String, val y: String, val geldigTot: Instant) {

    init {
        require(kid.isNotBlank()) { "een sleutel van een App Manager heeft een kid" }
        require(opP256(coordinaat(x), coordinaat(y))) { "sleutel $kid is geen punt op P-256" }
    }

    private companion object {
        val P256: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }

        /** RFC 7518 §6.2.1.2: precies de veldlengte, base64url zonder opvulling. */
        fun coordinaat(waarde: String): BigInteger {
            val bytes = Base64.getUrlDecoder().decode(waarde)

            require(bytes.size == 32 && !waarde.endsWith("=")) { "een coördinaat is 32 bytes, base64url zonder opvulling" }

            return BigInteger(1, bytes)
        }

        /** Een sleutel naast de curve zou een afnemer alleen afwijzingen opleveren; hier valt hij bij de start op. */
        fun opP256(x: BigInteger, y: BigInteger): Boolean {
            val curve = P256.curve
            val p = (curve.field as ECFieldFp).p

            return x < p && y < p && y.pow(2).mod(p) == (x.pow(3) + curve.a * x + curve.b).mod(p)
        }
    }
}

/** Een App Manager die apps credentials mag uitgeven, met zijn sleutels gesorteerd op `kid`. */
class AppManager(val oin: String, val naam: String, sleutels: Collection<AppManagerSleutel>) {

    val sleutels: List<AppManagerSleutel> = sleutels.sortedBy { it.kid }

    init {
        // Via de Oin-constructor: een App Manager zonder geldige OIN hoort de start te blokkeren.
        Oin(oin)

        require(naam.isNotBlank()) { "App Manager $oin heeft een naam nodig" }
        require(this.sleutels.isNotEmpty()) { "App Manager $oin heeft ten minste één sleutel nodig" }
        require(this.sleutels.map { it.kid }.toSet().size == this.sleutels.size) { "App Manager $oin heeft een kid dubbel" }
    }

    companion object {

        fun uit(config: Map<String, StelseldocumentConfig.AppManager>): List<AppManager> = config.map { (oin, appManager) ->
            AppManager(
                oin = oin,
                naam = appManager.naam(),
                sleutels = appManager.sleutels().map { (kid, sleutel) -> AppManagerSleutel(kid, sleutel.x(), sleutel.y(), sleutel.geldigTot()) },
            )
        }
    }
}
