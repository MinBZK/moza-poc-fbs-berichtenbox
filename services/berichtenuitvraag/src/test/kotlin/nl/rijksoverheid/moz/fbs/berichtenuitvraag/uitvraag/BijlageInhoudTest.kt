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
import java.io.InterruptedIOException
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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

        inhoud(byteArrayOf(1, 2, 3), rest = null).schrijfNaar(doel) { false }

        assertArrayEquals(byteArrayOf(1, 2, 3), doel.toByteArray())
    }

    @Test
    fun `met rest gaat eerst het begin door, geflusht, en dan de rest`() {
        val rest = ByteArray(40_000) { it.toByte() }
        val doel = OpnameStream()

        inhoud(byteArrayOf(9, 9), ByteArrayInputStream(rest)).schrijfNaar(doel) { false }

        assertArrayEquals(byteArrayOf(9, 9) + rest, doel.bytes.toByteArray())
        assertEquals(listOf(2, 2 + rest.size), doel.flushes, "een flush direct na het begin, en een na het laatste stuk")
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `een leesfout in de rest is een afgebroken overdracht`() {
        val oorzaak = IOException("verbinding verbroken")
        val doel = ByteArrayOutputStream()

        val fout = assertThrows(BijlageOverdrachtAfgebroken::class.java) {
            inhoud(byteArrayOf(1), FaaltNa(byteArrayOf(2, 3), oorzaak)).schrijfNaar(doel) { false }
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
            inhoud(byteArrayOf(1), ByteArrayInputStream(ByteArray(40_000))).schrijfNaar(doel) { false }
        }

        assertFalse(fout is BijlageOverdrachtAfgebroken)
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `sluiten vóór het schrijven sluit de magazijn-response, en daarna gaat er niets meer door`() {
        val bijlage = inhoud(byteArrayOf(1), ByteArrayInputStream(byteArrayOf(2)))
        val doel = ByteArrayOutputStream()

        bijlage.sluitAlsOngeschreven()

        // Geen stille terugkeer: die zou het antwoord netjes afronden, zonder bijlage.
        assertThrows(BijlageNietAfgeleverd::class.java) { bijlage.schrijfNaar(doel) { false } }

        assertEquals(0, doel.size())
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `sluiten na het schrijven doet niets meer`() {
        val bijlage = inhoud(byteArrayOf(1), ByteArrayInputStream(byteArrayOf(2)))

        bijlage.schrijfNaar(ByteArrayOutputStream()) { false }
        bijlage.sluitAlsOngeschreven()

        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `een tweede keer schrijven schrijft niets`() {
        val bijlage = inhoud(byteArrayOf(1), ByteArrayInputStream(byteArrayOf(2)))
        val tweede = ByteArrayOutputStream()

        bijlage.schrijfNaar(ByteArrayOutputStream()) { false }

        assertThrows(BijlageNietAfgeleverd::class.java) { bijlage.schrijfNaar(tweede) { false } }

        assertEquals(0, tweede.size())
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `een falende close maskeert geen geslaagde overdracht`() {
        every { response.close() } throws IllegalStateException("pool-close kapot")
        val doel = ByteArrayOutputStream()

        inhoud(byteArrayOf(1), ByteArrayInputStream(byteArrayOf(2))).schrijfNaar(doel) { false }

        assertArrayEquals(byteArrayOf(1, 2), doel.toByteArray())
    }

    @Test
    fun `een falende close maskeert geen afgebroken overdracht`() {
        every { response.close() } throws IllegalStateException("pool-close kapot")

        assertThrows(BijlageOverdrachtAfgebroken::class.java) {
            inhoud(byteArrayOf(1), FaaltNa(byteArrayOf(), IOException("weg"))).schrijfNaar(ByteArrayOutputStream()) { false }
        }
    }

    @Test
    fun `een afnemer die weg is, stopt het kopiëren`() {
        val gelezen = mutableListOf<Int>()
        val rest = object : InputStream() {
            private val bron = ByteArrayInputStream(ByteArray(100_000))

            override fun read(): Int = bron.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int = bron.read(b, off, len).also { gelezen.add(it) }
        }

        var rondes = 0

        assertThrows(BijlageNietAfgeleverd::class.java) {
            inhoud(byteArrayOf(1), rest).schrijfNaar(ByteArrayOutputStream()) { ++rondes > 2 }
        }

        assertEquals(2, gelezen.size, "na het signaal leest de uitvraag niets meer uit het magazijn")
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `een interrupt tijdens het lezen is geen afgebroken overdracht en zet de vlag terug`() {
        val fout = assertThrows(InterruptedIOException::class.java) {
            inhoud(byteArrayOf(1), FaaltNa(byteArrayOf(), InterruptedIOException("onderbroken"))).schrijfNaar(ByteArrayOutputStream()) { false }
        }

        assertEquals(InterruptedIOException::class.java, fout.javaClass, "geen afgebroken overdracht")
        assertTrue(Thread.interrupted(), "de interrupt-vlag hoort weer gezet te zijn")
    }

    @Test
    fun `sluiten tijdens het schrijven laat het sluiten aan de schrijver`() {
        val leestNu = CountDownLatch(1)
        val magVerder = CountDownLatch(1)
        val rest = object : InputStream() {
            override fun read(): Int = -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                leestNu.countDown()
                magVerder.await(10, TimeUnit.SECONDS)

                return -1
            }
        }

        val bijlage = inhoud(byteArrayOf(1), rest)
        val schrijver = thread { bijlage.schrijfNaar(ByteArrayOutputStream()) { false } }

        assertTrue(leestNu.await(10, TimeUnit.SECONDS))
        bijlage.sluitAlsOngeschreven()

        verify(exactly = 0) { response.close() }

        magVerder.countDown()
        schrijver.join(10_000)

        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `een write die lang op de afnemer wacht, valt op`() {
        val schrijftNu = CountDownLatch(1)
        val magVerder = CountDownLatch(1)
        val doel = object : OutputStream() {
            override fun write(b: Int) = Unit

            override fun write(b: ByteArray, off: Int, len: Int) {
                schrijftNu.countDown()
                magVerder.await(10, TimeUnit.SECONDS)
            }
        }

        val bijlage = inhoud(byteArrayOf(1), rest = null)

        assertFalse(bijlage.wachtLangerDan(Duration.ZERO), "zonder lopende write wacht er niets")

        val schrijver = thread { bijlage.schrijfNaar(doel) { false } }

        assertTrue(schrijftNu.await(10, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertTrue(bijlage.wachtLangerDan(Duration.ofMillis(10)))
        assertFalse(bijlage.wachtLangerDan(Duration.ofMinutes(1)))

        magVerder.countDown()
        schrijver.join(10_000)

        assertFalse(bijlage.wachtLangerDan(Duration.ZERO), "na de write wacht er niets meer")
    }

    @Test
    fun `alleen een bijlage zonder rest is volledig`() {
        assertArrayEquals(byteArrayOf(1, 2), inhoud(byteArrayOf(1, 2), rest = null).volledig)
        assertEquals(null, inhoud(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(3))).volledig)
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

    /** Onthoudt bij elke flush hoeveel bytes er toen geschreven waren. */
    private class OpnameStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        val flushes = mutableListOf<Int>()

        override fun write(b: Int) = bytes.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)

        override fun flush() {
            flushes.add(bytes.size())
        }
    }
}
