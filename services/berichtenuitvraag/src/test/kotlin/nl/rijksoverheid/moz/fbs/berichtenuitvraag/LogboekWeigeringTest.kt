package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import io.opentelemetry.api.trace.StatusCode
import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.WebApplicationException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.kern.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Pint welke uitkomst van een `@Logboek`-methode als weigering (`UNSET`) in het logboek komt en
 * welke als fout. Getoetst op een echte [LogboekContext]: wat de LDV-wrapper daarna met een
 * aangekondigde exceptie doet, toetst de wrapper zelf; dat de interceptor in de keten zit,
 * [LogboekWeigeringInterceptorTest].
 */
class LogboekWeigeringTest {

    private val context = LogboekContext()

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = [400, 404, 409, 410, 499])
    fun `een 4xx is een weigering en wordt aangekondigd`(status: Int) {
        val fout = WebApplicationException(status)

        val gegooid = assertThrows<WebApplicationException> { context.meldWeigering<Unit> { throw fout } }

        // Hetzelfde exemplaar: `LogboekContext.isExpected` vergelijkt op identiteit.
        assertSame(fout, gegooid)
        assertTrue(context.isExpected(fout), "HTTP $status hoort aangekondigd te zijn")
        assertEquals(StatusCode.UNSET, context.status)
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = [500, 502, 503])
    fun `een 5xx is een fout en wordt niet aangekondigd`(status: Int) {
        val fout = WebApplicationException(status)

        val gegooid = assertThrows<WebApplicationException> { context.meldWeigering<Unit> { throw fout } }

        assertSame(fout, gegooid)
        assertFalse(context.isExpected(fout), "HTTP $status hoort als fout in het logboek te komen")
    }

    @Test
    fun `een domeinregel die de mapper als 400 teruggeeft, is een weigering`() {
        val fout = DomainValidationException("BSN voldoet niet aan de elfproef")

        assertThrows<DomainValidationException> { context.meldWeigering<Unit> { throw fout } }

        assertTrue(context.isExpected(fout))
    }

    @Test
    fun `een overtreden Bean Validation-regel is een weigering`() {
        val fout = ConstraintViolationException("paginaGrootte moet minstens 1 zijn", emptySet())

        assertThrows<ConstraintViolationException> { context.meldWeigering<Unit> { throw fout } }

        assertTrue(context.isExpected(fout))
    }

    @Test
    fun `een andere exceptie is een fout`() {
        val fout = IllegalStateException("onverwacht")

        assertThrows<IllegalStateException> { context.meldWeigering<Unit> { throw fout } }

        assertFalse(context.isExpected(fout))
    }

    @Test
    fun `een WebApplicationException zonder response is een fout`() {
        assertFalse(isWeigering(WebApplicationException()))
    }

    @Test
    fun `zonder exceptie komt de waarde ongemoeid terug en wordt niets aangekondigd`() {
        assertEquals("lijst", context.meldWeigering { "lijst" })
        assertNull(context.expectedException)
    }
}
