package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.sql.SQLException
import java.util.concurrent.TimeUnit

/**
 * Een logger drukt van een meegegeven exceptie ook de message af, en die van elke cause en
 * suppressed fout. Een [Foutspoor] houdt de types en de stack, en laat de messages achter.
 */
class FoutspoorTest {

    private val pii = "Jan de Vries (jan@example.nl), BSN 999993653"

    @Test
    fun `het spoor draagt het type en de stack van de fout, niet de message`() {
        val fout = IOException("Upstream-fout voor $pii")

        val spoor = Foutspoor.van(fout)

        assertEquals("java.io.IOException", spoor.message)
        assertArrayEquals(fout.stackTrace, spoor.stackTrace)
        assertNull(spoor.cause)
        assertGeenPii(spoor)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 5])
    fun `elke cause in de keten houdt zijn type en verliest zijn message`(diepte: Int) {
        val fout = (1..diepte).fold(IllegalStateException("binnenste $pii") as Throwable) { binnenste, i ->
            IOException("laag $i $pii", binnenste)
        }

        val spoor = Foutspoor.van(fout)

        val keten = generateSequence(spoor as Throwable) { it.cause }.toList()
        assertEquals(diepte + 1, keten.size)
        assertEquals("java.lang.IllegalStateException", keten.last().message)
        assertGeenPii(spoor)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3])
    fun `suppressed fouten reizen mee zonder message`(aantal: Int) {
        val fout = IllegalStateException("opslag")
        repeat(aantal) { fout.addSuppressed(IOException("suppressed $it $pii")) }

        val spoor = Foutspoor.van(fout)

        assertEquals(List(aantal) { "java.io.IOException" }, spoor.suppressed.map { it.message })
        assertGeenPii(spoor)
    }

    @Test
    fun `een SQLException houdt haar SQLState, want die draagt de diagnose`() {
        val fout = IllegalStateException(
            "commit",
            SQLException("Batch entry 0 INSERT INTO t VALUES ('$pii') was aborted", "08006"),
        )

        val spoor = Foutspoor.van(fout)

        assertEquals("java.sql.SQLException (SQLState=08006)", spoor.cause!!.message)
        assertGeenPii(spoor)
    }

    @Test
    fun `een SQLState met vreemde tekens wordt niet overgenomen`() {
        val spoor = Foutspoor.van(SQLException("fout", "08\n$pii"))

        assertEquals("java.sql.SQLException", spoor.message)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `een cyclische keten eindigt`() {
        val a = IllegalStateException("a $pii")
        val b = IOException("b $pii", a)
        a.initCause(b)
        a.addSuppressed(b)

        val spoor = Foutspoor.van(a)

        assertEquals("java.io.IOException", spoor.cause!!.message)
        assertNull(spoor.cause!!.cause, "de cyclus wordt niet gevolgd")
        assertGeenPii(spoor)
    }

    @Test
    fun `een keten voorbij de grens wordt afgekapt`() {
        val fout = (1..Foutspoor.MAX_FOUTEN + 10).fold(IllegalStateException("0") as Throwable) { binnenste, i ->
            IllegalStateException("$i", binnenste)
        }

        val spoor = Foutspoor.van(fout)

        assertEquals(Foutspoor.MAX_FOUTEN, generateSequence(spoor as Throwable) { it.cause }.count())
    }

    private fun assertGeenPii(spoor: Foutspoor) {
        val weergave = spoor.stackTraceToString()

        listOf("Jan de Vries", "jan@example.nl", "999993653").forEach { fragment ->
            assertFalse(weergave.contains(fragment), "'$fragment' hoort niet in het spoor: $weergave")
        }

        assertTrue(weergave.contains(FoutspoorTest::class.java.name), "de stack moet behouden zijn: $weergave")
    }
}
