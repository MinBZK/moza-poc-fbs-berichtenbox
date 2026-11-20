package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.testhulp.Logvanger
import nl.rijksoverheid.moz.fbs.testhulp.herkenbareDelen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.URISyntaxException

/**
 * Een aanroeper kan een pad sturen waarvan Quarkus REST geen URI kan maken. De melding van die fout
 * citeert het hele pad met de query, en daar kan een ontvanger in staan. Via HTTP strandt zo'n pad al
 * eerder in Quarkus REST zelf (zie [MagazijnPadFilterTest]), dus deze tak toetsen we rechtstreeks.
 */
class MagazijnPadHerschrijfTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("fouten")
    fun `een pad dat geen URI oplevert, geeft een 404 en noemt het magazijn, niet het pad`(soort: String, fout: Exception) {
        val magazijnen = mockk<GesimuleerdeMagazijnen>()
        every { magazijnen.voorOin(OIN) } returns mockk<GesimuleerdMagazijn>()

        val antwoord = slot<Response>()
        val verzoek = mockk<ContainerRequestContext>()
        every { verzoek.uriInfo.path } returns "/magazijn/$OIN/api/v1/berichten/x"
        every { verzoek.uriInfo.requestUri } throws fout
        every { verzoek.abortWith(capture(antwoord)) } returns Unit

        val log = Logvanger().use { logvanger ->
            MagazijnPadFilter(magazijnen, MagazijnContext()).filter(verzoek)

            logvanger.tekst()
        }

        assertEquals(404, antwoord.captured.status)
        assertTrue(log.contains("magazijn=$OIN"), log)
        assertTrue(log.contains(fout.javaClass.name), "het type staat in het spoor: $log")

        herkenbareDelen(WAARDE).forEach { deel ->
            assertFalse(log.contains(deel), "'$deel' hoort niet in de log: $log")
        }
    }

    private companion object {
        const val OIN = "00000001003214345000"
        const val WAARDE = "999993653x"

        @JvmStatic
        fun fouten() = listOf(
            Arguments.of("IllegalArgumentException", IllegalArgumentException("Illegal character in path: /berichten/{$WAARDE}")),
            Arguments.of("URISyntaxException", URISyntaxException("/berichten/{$WAARDE}?afzender=$WAARDE", "Illegal character")),
        )
    }
}
