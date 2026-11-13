package nl.rijksoverheid.moz.fbs.democonsole.sessie

import jakarta.enterprise.context.ApplicationScoped
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wijst een sessie aan zonder haar hash in het paneel te zetten: een HMAC over de sessie-ZSET en de
 * hash, met een sleutel die deze console bij het opstarten zelf kiest. Zonder die sleutel is de
 * verwijzing niet terug te rekenen naar de hash, en ook niet te koppelen aan een sessie in een
 * Redis-dump.
 *
 * Stabiel zolang de sessie loopt en deze console draait, zodat het paneel niet bij elke uitlezing een
 * andere lijst ziet. Een verwijzing van vóór een herstart wijst niets meer aan. De console bewaart
 * niets: bij het beëindigen zoekt hij de lopende sessie op wier verwijzing overeenkomt.
 */
@ApplicationScoped
class Sessieverwijzer {

    private val sleutel = SecretKeySpec(ByteArray(SLEUTEL_BYTES).also(SecureRandom()::nextBytes), ALGORITME)

    fun voor(sessies: String, hash: String): String {
        val mac = Mac.getInstance(ALGORITME).apply { init(sleutel) }
        val uitkomst = mac.doFinal("$sessies\n$hash".toByteArray(Charsets.UTF_8))

        return Base64.getUrlEncoder().withoutPadding().encodeToString(uitkomst.copyOf(VERWIJZING_BYTES))
    }

    companion object {

        private const val ALGORITME = "HmacSHA256"

        private const val SLEUTEL_BYTES = 32

        /** 128 bits: genoeg om geen twee sessies dezelfde verwijzing te geven. */
        private const val VERWIJZING_BYTES = 16

        /** De lengte van 16 bytes in base64url zonder opvulling. */
        const val LENGTE = 22

        private val VORM = Regex("[A-Za-z0-9_-]{$LENGTE}")

        fun isGeldig(waarde: String): Boolean = VORM.matches(waarde)
    }
}
