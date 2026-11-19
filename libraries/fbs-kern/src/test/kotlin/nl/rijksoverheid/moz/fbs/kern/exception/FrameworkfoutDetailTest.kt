package nl.rijksoverheid.moz.fbs.kern.exception

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotAcceptableException
import jakarta.ws.rs.NotAllowedException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.NotSupportedException
import jakarta.ws.rs.WebApplicationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

class FrameworkfoutDetailTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("frameworkfouten")
    fun `een exception van het framework krijgt de vaste zin bij zijn soort`(
        exception: WebApplicationException,
        verwacht: String,
    ) {
        assertEquals(verwacht, FrameworkfoutDetail.voor(exception))
    }

    @Test
    fun `elke soort heeft zijn eigen zin, zonder annotatienaam`() {
        val zinnen = listOf(
            FrameworkfoutDetail.PAD_ONBEKEND,
            FrameworkfoutDetail.PARAMETER_ONGELDIG,
            FrameworkfoutDetail.METHODE_NIET_TOEGESTAAN,
            FrameworkfoutDetail.ACCEPT_NIET_TE_LEVEREN,
            FrameworkfoutDetail.MEDIATYPE_NIET_ONDERSTEUND,
        )

        assertEquals(zinnen.size, zinnen.toSet().size)
        zinnen.forEach { assertFalse(it.contains("@"), "geen annotatienaam: $it") }
    }

    /**
     * Een fout met dezelfde status die niet van het framework komt, zoals een 415 die van een
     * andere dienst wordt doorgegeven, zegt niets over het verzoek en krijgt geen vaste zin.
     */
    @ParameterizedTest
    @ValueSource(ints = [400, 403, 404, 405, 406, 409, 415, 500])
    fun `een gewone WebApplicationException krijgt geen vaste zin, ook niet met dezelfde status`(status: Int) {
        assertNull(FrameworkfoutDetail.voor(WebApplicationException("melding", status)))
    }

    @Test
    fun `een andere exception-soort van Jakarta REST krijgt geen vaste zin`() {
        assertNull(FrameworkfoutDetail.voor(BadRequestException("melding")))
        assertNull(FrameworkfoutDetail.voor(BadRequestException(IllegalStateException("kapot"))))
        assertNull(FrameworkfoutDetail.voor(ForbiddenException("melding")))
    }

    @Test
    fun `alleen een 404 met een conversiefout als oorzaak is een ongeldige parameter`() {
        assertTrue(FrameworkfoutDetail.isOngeldigeParameter(NotFoundException(NumberFormatException("abc"))))
        assertFalse(FrameworkfoutDetail.isOngeldigeParameter(NotFoundException("Unable to find matching target resource method")))
        assertFalse(FrameworkfoutDetail.isOngeldigeParameter(BadRequestException(IllegalStateException("kapot"))))
        assertFalse(FrameworkfoutDetail.isOngeldigeParameter(WebApplicationException(IllegalStateException("x"), 404)))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["HTTP 400 Bad Request", "HTTP 404 Not Found", "HTTP 405", " HTTP 415 Unsupported Media Type ", "HTTP 406 Not Acceptable"],
    )
    fun `de standaardmelding van het framework wordt herkend`(melding: String) {
        assertTrue(FrameworkfoutDetail.isStandaardmelding(melding))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "Bericht niet gevonden",
            "HTTP-header ontbreekt",
            "Zie HTTP 404 Not Found",
            "HTTP 4045",
            "HTTP 400 (header X-Ontvanger: 12)",
            "HTTP 409 Bericht is al gelezen",
            "HTTP 404 Not Found: bericht 12",
            "HTTP 499 Onbekend",
        ],
    )
    fun `een eigen melding is geen standaardmelding`(melding: String) {
        assertFalse(FrameworkfoutDetail.isStandaardmelding(melding))
    }

    companion object {

        @JvmStatic
        fun frameworkfouten(): Stream<Arguments> = Stream.of(
            Arguments.of(
                NotFoundException("Unable to find matching target resource method"),
                FrameworkfoutDetail.PAD_ONBEKEND,
            ),
            Arguments.of(NotFoundException(), FrameworkfoutDetail.PAD_ONBEKEND),
            Arguments.of(
                NotFoundException(NumberFormatException("For input string: \"abc\"")),
                FrameworkfoutDetail.PARAMETER_ONGELDIG,
            ),
            Arguments.of(NotAllowedException("GET"), FrameworkfoutDetail.METHODE_NIET_TOEGESTAAN),
            Arguments.of(
                NotAcceptableException("The accept header value did not match the value in @Produces"),
                FrameworkfoutDetail.ACCEPT_NIET_TE_LEVEREN,
            ),
            Arguments.of(
                NotSupportedException("The content-type header value did not match the value in @Consumes"),
                FrameworkfoutDetail.MEDIATYPE_NIET_ONDERSTEUND,
            ),
        )
    }
}
