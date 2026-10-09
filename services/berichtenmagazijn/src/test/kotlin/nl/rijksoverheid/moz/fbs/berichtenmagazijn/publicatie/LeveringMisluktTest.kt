package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Een [LeveringMislukt] bestaat alleen voor een levering die de afnemer zeker niet bereikte:
 * wie er een in handen heeft, mag "niet verstrekt" in het logboek zetten. De message is de
 * categorie, nooit de reden.
 */
class LeveringMisluktTest {

    @ParameterizedTest
    @MethodSource("onzekereLeveringen")
    fun `een levering die de afnemer mogelijk bereikte levert geen LeveringMislukt op`(
        resultaat: DownstreamResultaat.Mislukt,
    ) {
        assertNull(LeveringMislukt.van(resultaat))
    }

    @ParameterizedTest
    @MethodSource("zekerNietVerzonden")
    fun `een zeker niet verzonden levering draagt alleen haar categorie`(
        resultaat: DownstreamResultaat.Mislukt,
        categorie: String,
    ) {
        val fout = LeveringMislukt.van(resultaat)!!

        assertEquals(categorie, fout.message)
        assertNull(fout.cause)
        assertEquals(0, fout.stackTrace.size, "de stacktrace wijst alleen naar de factory")
    }

    companion object {
        private const val REDEN = "geweigerd voor 999993653"

        @JvmStatic
        fun onzekereLeveringen(): List<DownstreamResultaat.Mislukt> = listOf(
            DownstreamResultaat.Timeout.bijLezen(REDEN),
            DownstreamResultaat.NetwerkFout.onderweg(REDEN),
            DownstreamResultaat.HttpFout(statusCode = 503, retryAfter = null, reden = REDEN),
        )

        @JvmStatic
        fun zekerNietVerzonden(): List<Arguments> = listOf(
            Arguments.of(DownstreamResultaat.Timeout.bijVerbinden(REDEN), "Timeout"),
            Arguments.of(DownstreamResultaat.NetwerkFout.geenVerbinding(REDEN), "NetwerkFout"),
            Arguments.of(DownstreamResultaat.SerialisatieFout.voorVerzending(REDEN), "SerialisatieFout"),
            Arguments.of(DownstreamResultaat.ConfiguratieFout.voorVerzending(REDEN), "ConfiguratieFout"),
            Arguments.of(DownstreamResultaat.OpbouwFout.voorVerzending(REDEN), "OpbouwFout"),
        )
    }
}
