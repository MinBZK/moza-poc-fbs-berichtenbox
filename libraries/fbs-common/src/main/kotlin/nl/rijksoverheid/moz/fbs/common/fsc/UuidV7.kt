package nl.rijksoverheid.moz.fbs.common.fsc

import java.security.SecureRandom
import java.util.Random
import java.util.UUID

/**
 * UUID-versie-7-generator (RFC 9562): een 48-bit unix-ms-tijdstip in de hoge bits maakt
 * waarden tijd-geordend, wat de FSC-outway als `Fsc-Transaction-Id` eist. De JDK genereert
 * zelf alleen v4 (`UUID.randomUUID()`); dit is de enige plek die het v7-bitpatroon opbouwt.
 *
 * `epochMilli`/`rnd` zijn injecteerbaar zodat tests het tijdstip- en random-deel deterministisch
 * kunnen vastzetten.
 */
object UuidV7 {

    private const val VERSION_7 = 0x7L
    private const val VARIANT_RFC4122 = 0x2L
    private const val TIJDSTIP_MASK_48_BIT = 0xFFFFFFFFFFFFL
    private const val RAND_A_MASK_12_BIT = 0x0FFF
    private const val RAND_B_MASK_62_BIT = 0x3FFFFFFFFFFFFFFFL

    fun generate(epochMilli: Long = System.currentTimeMillis(), rnd: Random = SecureRandom()): UUID {
        val tijdstip = (epochMilli and TIJDSTIP_MASK_48_BIT) shl 16
        val randA = (rnd.nextInt() and RAND_A_MASK_12_BIT).toLong()
        val mostSigBits = tijdstip or (VERSION_7 shl 12) or randA

        val randB = rnd.nextLong() and RAND_B_MASK_62_BIT
        val leastSigBits = randB or (VARIANT_RFC4122 shl 62)

        return UUID(mostSigBits, leastSigBits)
    }
}
