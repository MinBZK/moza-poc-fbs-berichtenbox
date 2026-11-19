package nl.rijksoverheid.moz.fbs.kern.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class VeldpadTest {

    @ParameterizedTest
    @MethodSource("paden")
    fun `veldnamen en posities komen in de vorm die een operator leest`(segmenten: List<Any?>, verwacht: String) {
        assertEquals(verwacht, Veldpad.van(segmenten))
    }

    @ParameterizedTest
    @ValueSource(strings = ["999993653", "BSN:999993653", "a99", "x5c99", "ont vanger", "<script>", "", "ä"])
    fun `een sleutel die niet op een veldnaam lijkt, komt niet in het pad`(sleutel: String) {
        assertEquals("ontvanger.?.waarde", Veldpad.van(listOf("ontvanger", sleutel, "waarde")))
    }

    @Test
    fun `een veldnaam met losse cijfers blijft staan`() {
        assertEquals("x5c.a1b2", Veldpad.van(listOf("x5c", "a1b2")))
    }

    @Test
    fun `een segment van een ander soort, of een negatieve positie, is onleesbaar`() {
        assertEquals("?.?.?", Veldpad.van(listOf(null, 1L, -1)))
    }

    @Test
    fun `een veldnaam langer dan een model ooit heeft, is onleesbaar`() {
        assertEquals("?", Veldpad.van(listOf("a".repeat(65))))
        assertEquals("a".repeat(64), Veldpad.van(listOf("a".repeat(64))))
    }

    @Test
    fun `een pad dieper dan een model ooit is, wordt afgekapt`() {
        val pad = Veldpad.van(List(Veldpad.MAX_SEGMENTEN + 1) { "a" })

        assertEquals(List(Veldpad.MAX_SEGMENTEN) { "a" }.joinToString(".") + ".…", pad)
    }

    companion object {
        @JvmStatic
        fun paden() = listOf(
            Arguments.of(emptyList<Any?>(), ""),
            Arguments.of(listOf("ontvanger"), "ontvanger"),
            Arguments.of(listOf("ontvanger", "waarde"), "ontvanger.waarde"),
            Arguments.of(listOf("bijlagen", 0, "naam"), "bijlagen[0].naam"),
            Arguments.of(listOf(2, "naam"), "[2].naam"),
            Arguments.of(listOf("_links", "self"), "_links.self"),
        )
    }
}
