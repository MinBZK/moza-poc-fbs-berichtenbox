package nl.rijksoverheid.moz.fbs.common.fsc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class UuidV7Test {

    @Test
    fun `generate levert version 7 en variant 2 (RFC 4122)`() {
        val uuid = UuidV7.generate()

        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
    }

    @Test
    fun `twee opeenvolgende aanroepen leveren verschillende waarden`() {
        val eerste = UuidV7.generate()
        val tweede = UuidV7.generate()

        assertNotEquals(eerste, tweede)
    }

    @Test
    fun `oplopende epochMilli levert lexicografisch niet-dalende waarden (tijd-geordend)`() {
        val rnd = Random(42)

        val vroeg = UuidV7.generate(epochMilli = 1_000_000L, rnd = rnd)
        val laat = UuidV7.generate(epochMilli = 2_000_000L, rnd = rnd)

        assertTrue(vroeg.toString() <= laat.toString()) {
            "verwacht $vroeg <= $laat (tijd-geordend op oplopende epochMilli)"
        }
    }

    @Test
    fun `epochMilli = 0 levert een geldige v7-uuid met tijdstip-deel op nul`() {
        val uuid = UuidV7.generate(epochMilli = 0L)

        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
        assertTrue(uuid.toString().startsWith("00000000-0000-7")) {
            "verwacht dat het 48-bit tijdstip-deel nul is: $uuid"
        }
    }

    @Test
    fun `deterministische random levert reproduceerbare uuid bij gelijke epochMilli`() {
        val eerste = UuidV7.generate(epochMilli = 500_000L, rnd = Random(7))
        val tweede = UuidV7.generate(epochMilli = 500_000L, rnd = Random(7))

        assertEquals(eerste, tweede)
    }
}
