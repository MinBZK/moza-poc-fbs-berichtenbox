package nl.rijksoverheid.moz.fbs.democonsole.simulator

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Zonder deze mapper komt een afgewezen beheeraanroep als een geslaagde uitkomst met nullen binnen:
 * `LeegUitkomst` en `SeedUitkomst` bestaan enkel uit primitieven, en die vult jackson-module-kotlin
 * met hun primitieve default zodra het veld ontbreekt. Het paneel meldt dan "gelukt, 0 berichten"
 * terwijl er niets geleegd of gevuld is.
 */
class SimulatorBeheerFoutTest {

    private val mapper = SimulatorBeheerFout()

    private fun respons(code: Int, body: String? = null) = mockk<Response>(relaxed = true) {
        every { status } returns code
        every { readEntity(String::class.java) } returns body
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 409, 500, 503])
    fun `elke foutstatus wordt opgepakt`(status: Int) {
        assertTrue(mapper.handles(status, null), "HTTP $status hoort een fout te worden")
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 201, 204])
    fun `een geslaagd antwoord blijft ongemoeid`(status: Int) {
        assertFalse(mapper.handles(status, null), "HTTP $status hoort gewoon gedeserialiseerd te worden")
    }

    @Test
    fun `een 401 noemt het token en de variabele waar het vandaan komt`() {
        // De simulator zegt bewust niet wát er mis is met het token; noemt de console dat ook niet,
        // dan gaat de bediener de keten in terwijl er twee env-velden uit elkaar lopen.
        val fout = mapper.toThrowable(respons(401, """{"title":"Unauthorized"}"""))

        assertTrue(
            fout.message!!.contains("MAGAZIJN_SIMULATOR_BEHEER_TOKEN"),
            "melding moet de variabele noemen, was: ${fout.message}",
        )
    }

    @Test
    fun `een andere fout draagt de status en de reden van de simulator`() {
        val fout = mapper.toThrowable(respons(503, "simulator start nog op"))

        assertEquals("De simulator beantwoordde het beheerpad met HTTP 503: simulator start nog op", fout.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `een lege body laat de status over in plaats van een losse dubbele punt`(body: String) {
        assertEquals("De simulator beantwoordde het beheerpad met HTTP 500", mapper.toThrowable(respons(500, body)).message)
    }

    @Test
    fun `een onleesbare body maakt van de fout geen andere fout`() {
        // readEntity gooit zodra de stream al verbruikt of afgebroken is. Zou dat hier opborrelen,
        // dan verving een leesfout de statuscode die de bediener juist nodig heeft.
        val respons = mockk<Response>(relaxed = true) {
            every { status } returns 500
            every { readEntity(String::class.java) } throws IllegalStateException("stream gesloten")
        }

        assertEquals("De simulator beantwoordde het beheerpad met HTTP 500", mapper.toThrowable(respons).message)
    }
}
