package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Een timeout van 0 of lager zet de bescherming stil uit in plaats van hem korter te zetten:
 * de REST-client leest 0 als "onbegrensd wachten", waarna een hangend magazijn een socket en
 * een worker-thread vasthoudt tot de andere kant iets doet. Zo'n instelling hoort de dienst
 * tegen te houden bij het opstarten, niet stilzwijgend een gat te maken.
 */
class MagazijnClientFactoryTimeoutTest {

    private fun factory(connectTimeoutMs: Long = 2000, readTimeoutMs: Long = 12000) =
        MagazijnClientFactory(mockk<Magazijnregister>(relaxed = true), connectTimeoutMs, readTimeoutMs, testTlsRegistry())

    @ParameterizedTest
    @ValueSource(longs = [0, -1, -2000, Long.MIN_VALUE])
    fun `een connect-timeout van nul of lager wordt geweigerd`(waarde: Long) {
        val ex = assertThrows<IllegalArgumentException> { factory(connectTimeoutMs = waarde).valideerTimeouts() }

        assertTrue(
            ex.message!!.contains("connect-timeout-ms"),
            "melding moet de property noemen die aangepast moet worden: ${ex.message}",
        )
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1, -12000, Long.MIN_VALUE])
    fun `een read-timeout van nul of lager wordt geweigerd`(waarde: Long) {
        val ex = assertThrows<IllegalArgumentException> { factory(readTimeoutMs = waarde).valideerTimeouts() }

        assertTrue(
            ex.message!!.contains("read-timeout-ms"),
            "melding moet de property noemen die aangepast moet worden: ${ex.message}",
        )
    }

    @ParameterizedTest
    @ValueSource(longs = [1, 2000, Long.MAX_VALUE])
    fun `een positieve timeout komt door`(waarde: Long) {
        assertDoesNotThrow { factory(connectTimeoutMs = waarde, readTimeoutMs = waarde).valideerTimeouts() }
    }

    /**
     * De tabel hierboven zegt niets over de vraag of de controle ook echt bij het opstarten
     * draait. Zonder deze test blijft alles groen als de aanroep uit `init()` verdwijnt, en dan
     * start de dienst alsnog met een uitgeschakelde bescherming.
     */
    @Test
    fun `de controle hangt aan het opstarten van de factory`() {
        val ex = assertThrows<IllegalArgumentException> { factory(connectTimeoutMs = 0).init() }

        assertTrue(ex.message!!.contains("connect-timeout-ms"), "melding: ${ex.message}")
    }
}
