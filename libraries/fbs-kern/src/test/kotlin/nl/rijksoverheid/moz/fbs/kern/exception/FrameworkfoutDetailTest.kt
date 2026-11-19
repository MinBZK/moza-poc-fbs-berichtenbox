package nl.rijksoverheid.moz.fbs.kern.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FrameworkfoutDetailTest {

    @ParameterizedTest
    @ValueSource(ints = [404, 405, 406, 415])
    fun `een status die het framework produceert krijgt een vaste Nederlandse zin`(status: Int) {
        val detail = FrameworkfoutDetail.voorStatus(status)

        assertNotNull(detail)
        assertFalse(detail!!.contains("@"), "geen annotatienaam in de tekst: $detail")
        assertTrue(detail.endsWith("."), "een volzin: $detail")
    }

    @Test
    fun `elke status heeft zijn eigen zin`() {
        val zinnen = listOf(404, 405, 406, 415).map { FrameworkfoutDetail.voorStatus(it) }

        assertEquals(zinnen.size, zinnen.toSet().size)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 409, 410, 422, 429, 500, 503])
    fun `een status uit eigen code krijgt geen vaste zin`(status: Int) {
        assertNull(FrameworkfoutDetail.voorStatus(status))
    }

    @ParameterizedTest
    @ValueSource(strings = ["HTTP 400 Bad Request", "HTTP 404 Not Found", "HTTP 405", " HTTP 415 Unsupported Media Type "])
    fun `de standaardmelding van het framework wordt herkend`(melding: String) {
        assertTrue(FrameworkfoutDetail.isStandaardmelding(melding))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "Bericht niet gevonden", "HTTP-header ontbreekt", "Zie HTTP 404 Not Found", "HTTP 4045"])
    fun `een eigen melding is geen standaardmelding`(melding: String) {
        assertFalse(FrameworkfoutDetail.isStandaardmelding(melding))
    }
}
