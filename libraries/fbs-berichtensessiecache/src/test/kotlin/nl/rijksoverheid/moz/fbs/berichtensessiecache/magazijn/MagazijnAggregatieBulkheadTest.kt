package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.smallrye.mutiny.Uni
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Concurrency-bulkhead via [MagazijnAggregatieBulkhead.begrensd]: pint de acquire/release-balans
 * op élke terminatie (succes/fout/cancel/taak-opbouwfout), de afwijzing bij een vol bulkhead, en
 * de fail-fast config-validatie. Deterministisch, zonder eigen threads.
 */
class MagazijnAggregatieBulkheadTest {

    @Test
    fun `taak draait onder een permit en geeft die vrij bij succes`() {
        val bulkhead = MagazijnAggregatieBulkhead(maxConcurrent = 1)

        val uitkomst = bulkhead.begrensd(
            afgewezen = { Uni.createFrom().item("AFGEWEZEN") },
            taak = { Uni.createFrom().item("OK") },
        ).await().indefinitely()

        assertEquals("OK", uitkomst)
        assertEquals(1, bulkhead.vrijePermits(), "permit vrijgegeven na succes")
    }

    @Test
    fun `permit vrijgegeven bij een falende taak`() {
        val bulkhead = MagazijnAggregatieBulkhead(maxConcurrent = 1)

        val uni = bulkhead.begrensd(
            afgewezen = { Uni.createFrom().item("AFGEWEZEN") },
            taak = { Uni.createFrom().failure(RuntimeException("boem")) },
        )

        assertThrows<RuntimeException> { uni.await().indefinitely() }
        assertEquals(1, bulkhead.vrijePermits(), "permit vrijgegeven na fout")
    }

    @Test
    fun `permit vrijgegeven als het opbouwen van de taak-Uni gooit`() {
        // Regressie: gooit de taak-lambda vóór er een Uni (met onTermination) is, dan is er niets om
        // de permit op terminatie vrij te geven — begrensd MOET hem alsnog vrijgeven, anders lekt hij.
        val bulkhead = MagazijnAggregatieBulkhead(maxConcurrent = 1)

        val uni = bulkhead.begrensd<String>(
            afgewezen = { Uni.createFrom().item("AFGEWEZEN") },
            taak = { throw IllegalStateException("opbouw faalt") },
        )

        assertThrows<IllegalStateException> { uni.await().indefinitely() }
        assertEquals(1, bulkhead.vrijePermits(), "permit vrijgegeven ondanks opbouwfout")
    }

    @Test
    fun `vol bulkhead wijst af zonder een permit te claimen`() {
        val bulkhead = MagazijnAggregatieBulkhead(maxConcurrent = 1)

        // Houd de enige permit vast met een taak die niet termineert (nooit emit, niet geannuleerd).
        val vastgehouden = bulkhead.begrensd(
            afgewezen = { Uni.createFrom().item("AFGEWEZEN") },
            taak = { Uni.createFrom().nothing<String>() },
        ).subscribe().with({}, {})

        assertEquals(0, bulkhead.vrijePermits(), "permit geclaimd door de lopende taak")

        val uitkomst = bulkhead.begrensd(
            afgewezen = { Uni.createFrom().item("AFGEWEZEN") },
            taak = { Uni.createFrom().item("OK") },
        ).await().indefinitely()

        assertEquals("AFGEWEZEN", uitkomst, "bulkhead vol → afgewezen-tak")
        assertEquals(0, bulkhead.vrijePermits(), "afwijzing claimt geen extra permit")

        // Annuleer de vasthoudende subscription: onTermination(cancel) geeft de permit vrij.
        vastgehouden.cancel()

        assertEquals(1, bulkhead.vrijePermits(), "permit vrijgegeven bij cancel")
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
