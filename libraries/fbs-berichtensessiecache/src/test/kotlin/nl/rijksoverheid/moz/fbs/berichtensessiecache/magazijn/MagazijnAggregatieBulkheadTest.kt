package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Concurrency-bulkhead: deterministisch (zonder threads) gepind op claim/vrijgave-balans,
 * uitputting bij een vol bulkhead en de fail-fast config-validatie.
 */
class MagazijnAggregatieBulkheadTest {

    @Test
    fun `claimt tot maxConcurrent en wijst daarna af`() {
        val bulkhead = MagazijnAggregatieBulkhead(maxConcurrent = 2)

        assertTrue(bulkhead.probeerBinnen(), "eerste permit")
        assertTrue(bulkhead.probeerBinnen(), "tweede permit")

        assertFalse(bulkhead.probeerBinnen(), "bulkhead vol → afwijzen")
        assertEquals(0, bulkhead.vrijePermits())
    }

    @Test
    fun `verlaat geeft een permit vrij voor een volgende call`() {
        val bulkhead = MagazijnAggregatieBulkhead(maxConcurrent = 1)

        assertTrue(bulkhead.probeerBinnen())
        assertFalse(bulkhead.probeerBinnen(), "vol")

        bulkhead.verlaat()

        assertEquals(1, bulkhead.vrijePermits())
        assertTrue(bulkhead.probeerBinnen(), "permit weer beschikbaar")
    }

    @Test
    fun `start met alle permits vrij`() {
        val bulkhead = MagazijnAggregatieBulkhead(maxConcurrent = 5)

        assertEquals(5, bulkhead.vrijePermits())
    }

    @Test
    fun `niet-positieve maxConcurrent faalt fail-fast bij constructie`() {
        // Het init-blok gooit al bij constructie — vóór de Semaphore-constructie, die zelf bij
        // 0/negatief niet gooit (en dan een altijd-volle bulkhead = stille OVERBELAST zou geven).
        assertThrows<IllegalArgumentException> {
            MagazijnAggregatieBulkhead(maxConcurrent = 0)
        }

        assertThrows<IllegalArgumentException> {
            MagazijnAggregatieBulkhead(maxConcurrent = -1)
        }
    }
}
