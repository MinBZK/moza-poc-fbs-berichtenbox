package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LogboekMetingTest {

    private val registry = SimpleMeterRegistry()

    private fun geweigerd() = registry.find("fbs.logboek.geweigerd").counter()?.count() ?: 0.0

    private fun zonderLogregel() =
        registry.find("fbs.logboek.waarschuwing").tag("code", LogboekMeting.ZONDER_LOGREGEL_TOKEN).counter()?.count() ?: 0.0

    // Een lezend verzoek krijgt een 500 in plaats van de gegevens: er is niets verstrekt.
    @ParameterizedTest
    @ValueSource(strings = ["GET", "HEAD", "OPTIONS"])
    fun `een lezend verzoek dat het logboek tegenhield, is geweigerd`(methode: String) {
        LogboekMeting.schrijffout(methode, "/api/v1/berichten", registry)

        assertEquals(1.0, geweigerd())
        assertEquals(0.0, zonderLogregel())
    }

    // De wrapper schrijft pas ná de methode: de wijziging staat, de logregel ontbreekt.
    @Test
    fun `een wijzigend verzoek is doorgevoerd zonder logregel`() {
        LogboekMeting.schrijffout("POST", "/api/v1/sessies", registry)

        assertEquals(0.0, geweigerd())
        assertEquals(1.0, zonderLogregel())
    }
}
