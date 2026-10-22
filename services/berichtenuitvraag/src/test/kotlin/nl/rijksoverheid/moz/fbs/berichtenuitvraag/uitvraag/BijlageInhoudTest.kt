package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Hoe [BijlageInhoud] een bijlage doorgeeft: het begin, dan meteen een flush, dan de rest; een
 * leesfout herkenbaar als afgebroken overdracht, een schrijffout niet; en de magazijn-response
 * precies één keer dicht, wat er ook gebeurt.
 */
class BijlageInhoudTest {

    private val response = mockk<Response>(relaxed = true)

    private fun inhoud(begin: ByteArray, rest: InputStream?) =
        BijlageInhoud("application/pdf", "aanslag.pdf", null, begin, rest, if (rest == null) null else response)

    @Test
    fun `zonder rest gaat alleen het begin door`() {
        val doel = ByteArrayOutputStream()

        inhoud(byteArrayOf(1, 2, 3), rest = null).schrijfNaar(doel)

        assertArrayEquals(byteArrayOf(1, 2, 3), doel.toByteArray())
    }

    @Test
    fun `met rest gaat eerst het begin door, geflusht, en dan de rest`() {
        val rest = ByteArray(40_000) { it.toByte() }
        val doel = OpnameStream()

        inhoud(byteArrayOf(9, 9), ByteArrayInputStream(rest)).schrijfNaar(doel)

        assertArrayEquals(byteArrayOf(9, 9) + rest, doel.bytes.toByteArray())
        assertEquals(2, doel.geflushtNa, "de flush hoort direct na het begin te komen")
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `een leesfout in de rest is een afgebroken overdracht`() {
        val oorzaak = IOException("verbinding verbroken")
        val doel = ByteArrayOutputStream()

        val fout = assertThrows(BijlageOverdrachtAfgebroken::class.java) {
            inhoud(byteArrayOf(1), FaaltNa(byteArrayOf(2, 3), oorzaak)).schrijfNaar(doel)
        }

        assertSame(oorzaak, fout.cause)
        assertArrayEquals(byteArrayOf(1, 2, 3), doel.toByteArray(), "wat er wel kwam, is doorgegeven")
        verify(exactly = 1) { response.close() }
    }

    // Het onderscheid tussen lezen en schrijven zit in het kopiëren van de rest; daarom ook een
    // schrijffout ná de flush, en niet alleen op het begin.
    @ParameterizedTest
    @EnumSource(Schrijfmoment::class)
    fun `een schrijffout is geen afgebroken overdracht`(moment: Schrijfmoment) {
        val doel = FaaltBij(moment)

        val fout = assertThrows(IOException::class.java) {
            inhoud(byteArrayOf(1), ByteArrayInputStream(ByteArray(40_000))).schrijfNaar(doel)
        }

        assertFalse(fout is BijlageOverdrachtAfgebroken)
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `sluiten vóór het schrijven sluit de magazijn-response, en daarna gaat er niets meer door`() {
        val bijlage = inhoud(byteArrayOf(1), ByteArrayInputStream(byteArrayOf(2)))
        val doel = ByteArrayOutputStream()

        bijlage.sluitAlsOngeschreven()
        bijlage.schrijfNaar(doel)

        assertEquals(0, doel.size())
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `sluiten na het schrijven doet niets meer`() {
        val bijlage = inhoud(byteArrayOf(1), ByteArrayInputStream(byteArrayOf(2)))

        bijlage.schrijfNaar(ByteArrayOutputStream())
        bijlage.sluitAlsOngeschreven()

        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `een tweede keer schrijven schrijft niets`() {
        val bijlage = inhoud(byteArrayOf(1), ByteArrayInputStream(byteArrayOf(2)))
        val tweede = ByteArrayOutputStream()

        bijlage.schrijfNaar(ByteArrayOutputStream())
        bijlage.schrijfNaar(tweede)

        assertEquals(0, tweede.size())
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `een falende close maskeert geen geslaagde overdracht`() {
        every { response.close() } throws IllegalStateException("pool-close kapot")
        val doel = ByteArrayOutputStream()

        inhoud(byteArrayOf(1), ByteArrayInputStream(byteArrayOf(2))).schrijfNaar(doel)

        assertArrayEquals(byteArrayOf(1, 2), doel.toByteArray())
    }

    @Test
    fun `een falende close maskeert geen afgebroken overdracht`() {
        every { response.close() } throws IllegalStateException("pool-close kapot")

        assertThrows(BijlageOverdrachtAfgebroken::class.java) {
            inhoud(byteArrayOf(1), FaaltNa(byteArrayOf(), IOException("weg"))).schrijfNaar(ByteArrayOutputStream())
        }
    }

    @Test
    fun `de bestandsnaam staat niet in toString`() {
        assertTrue("aanslag.pdf" !in inhoud(byteArrayOf(1), rest = null).toString())
    }

    /** Geeft [eerst] en gooit daarna [fout]. */
    private class FaaltNa(eerst: ByteArray, private val fout: IOException) : InputStream() {
        private val bron = ByteArrayInputStream(eerst)

        override fun read(): Int = bron.read().takeIf { it >= 0 } ?: throw fout

        override fun read(b: ByteArray, off: Int, len: Int): Int = bron.read(b, off, len).takeIf { it >= 0 } ?: throw fout
    }

    enum class Schrijfmoment { BEGIN, FLUSH, REST }

    /** Gooit bij [moment]: bij het begin, bij de flush daarna, of bij de eerste write van de rest. */
    private class FaaltBij(private val moment: Schrijfmoment) : OutputStream() {
        private var geflusht = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (moment == Schrijfmoment.BEGIN || (moment == Schrijfmoment.REST && geflusht)) throw IOException("afnemer weg")
        }

        override fun flush() {
            if (moment == Schrijfmoment.FLUSH) throw IOException("afnemer weg")

            geflusht = true
        }
    }

    /** Onthoudt hoeveel bytes er bij de eerste flush geschreven waren. */
    private class OpnameStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var geflushtNa: Int? = null

        override fun write(b: Int) = bytes.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)

        override fun flush() {
            if (geflushtNa == null) geflushtNa = bytes.size()
        }
    }
}
