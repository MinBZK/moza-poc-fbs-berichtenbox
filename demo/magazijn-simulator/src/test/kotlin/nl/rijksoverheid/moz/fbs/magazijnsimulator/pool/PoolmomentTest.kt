package nl.rijksoverheid.moz.fbs.magazijnsimulator.pool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration

/**
 * De pool-regel is het enige zicht op wachtende aanvragen tijdens een demo. Verdwijnt hij, of gaat
 * hij bij elke tick opnieuw, dan is hij precies dan onbruikbaar wanneer je hem nodig hebt.
 */
class PoolmomentTest {

    private fun moment(
        inGebruik: Long = 3,
        vrij: Long = 2,
        wachtend: Long = 0,
        max: Int = 120,
        piek: Long = 5,
        opgezet: Long = 5,
        vernietigd: Long = 0,
        gemiddeld: Duration = Duration.ofMillis(4),
        langst: Duration = Duration.ofMillis(40),
        totaal: Duration = Duration.ofMillis(400),
    ) = Poolmoment(inGebruik, vrij, wachtend, max, piek, opgezet, vernietigd, gemiddeld, langst, totaal)

    @Test
    fun `de regel noemt bezetting, opzetten en wachten`() {
        val regel = moment(inGebruik = 18, vrij = 2, wachtend = 7, piek = 20, opgezet = 20).regel()

        assertEquals(
            "pool: 18 in gebruik, 2 vrij, 7 wachtend van max 120 | piek 20 | opgezet 20, vernietigd 0 | " +
                "wachten gem 4ms, langst 40ms, totaal 400ms",
            regel,
        )
    }

    /** Seconden met een decimaal zodra het meer dan een seconde is; nanoseconden leest niemand mee. */
    @ParameterizedTest
    @CsvSource("999, 999ms", "1000, 1.0s", "12400, 12.4s", "0, 0ms")
    fun `wachttijden krijgen een leesbare eenheid`(millis: Long, verwacht: String) {
        val regel = moment(totaal = Duration.ofMillis(millis)).regel()

        assertTrue(regel.endsWith("totaal $verwacht"), "verwachtte '$verwacht' aan het eind van: $regel")
    }

    @Test
    fun `de eerste meting is altijd nieuw`() {
        assertTrue(moment().verschiltVan(null))
    }

    @Test
    fun `een ongewijzigde meting levert geen tweede regel op`() {
        assertFalse(moment().verschiltVan(moment()))
    }

    /**
     * Elk van de tellers apart, want een vergelijking die er één vergeet laat juist die beweging
     * onzichtbaar — en dat is niet te zien aan een regel die er verder normaal uitziet.
     */
    @Test
    fun `elke teller die beweegt levert een nieuwe regel op`() {
        val basis = moment()
        val varianten = listOf(
            "in gebruik" to basis.copy(inGebruik = 4),
            "vrij" to basis.copy(vrij = 1),
            "wachtend" to basis.copy(wachtend = 1),
            "piek" to basis.copy(piek = 6),
            "opgezet" to basis.copy(opgezet = 6),
            "vernietigd" to basis.copy(vernietigd = 1),
            "wachten gemiddeld" to basis.copy(wachtenGemiddeld = Duration.ofMillis(5)),
            "wachten langst" to basis.copy(wachtenLangst = Duration.ofMillis(41)),
            "wachten totaal" to basis.copy(wachtenTotaal = Duration.ofMillis(401)),
        )

        varianten.forEach { (wat, variant) ->
            assertTrue(variant.verschiltVan(basis), "een gewijzigde '$wat' hoort een nieuwe regel op te leveren")
        }
    }

    /** `max` is een instelling en geen meting: een herstart met een andere pool is geen beweging. */
    @Test
    fun `een andere ingestelde maximumgrootte telt niet als beweging`() {
        assertFalse(moment(max = 20).verschiltVan(moment(max = 120)))
    }
}
