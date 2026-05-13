package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * Unit-tests voor [RetryBeleid]. Pure functie, geen Quarkus-context nodig.
 *
 * Borgt:
 *  - exponentiële backoff sequentie 1s, 2s, 4s, ... voor basis=1s
 *  - `null` bij pogingen >= max → MISLUKT
 *  - cap voorkomt runaway backoff
 *  - jitter deterministisch per claimId; verschillende claimIds → andere retry-tijden
 */
class RetryBeleidTest {

    private val basis = Duration.ofSeconds(1)
    private val cap = Duration.ofHours(1)
    private val nu = Instant.parse("2026-05-12T10:00:00Z")

    @Test
    fun `pogingen 1 levert vertraging tussen basis en basis + 25 procent`() {
        val volgende = RetryBeleid.volgendePoging(
            nu = nu,
            pogingenNaFout = 1,
            maxPogingen = 5,
            basis = basis,
            cap = cap,
            claimId = 42L,
        )
        assertNotNull(volgende)
        val vertragingMs = Duration.between(nu, volgende).toMillis()
        // 1s basis * 2^1 = 2s; jitter 0..25% → 2.0..2.5s
        assertTrue(vertragingMs in 2_000..2_500, "verwacht 2000..2500 ms, kreeg $vertragingMs")
    }

    @Test
    fun `pogingen exponentieel groeien met basis=1s tot pogingen=4`() {
        val basisMs = listOf(1, 2, 3, 4).map { pogingen ->
            val v = RetryBeleid.volgendePoging(nu, pogingen, maxPogingen = 10, basis = basis, cap = cap, claimId = 0L)!!
            Duration.between(nu, v).toMillis()
        }
        // basis=1s; 2^1..2^4 = 2..16s. claimId=0 → jitter=0.
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L), basisMs)
    }

    @Test
    fun `pogingen gelijk aan max levert null (MISLUKT)`() {
        val volgende = RetryBeleid.volgendePoging(
            nu = nu,
            pogingenNaFout = 5,
            maxPogingen = 5,
            basis = basis,
            cap = cap,
            claimId = 0L,
        )
        assertNull(volgende)
    }

    @Test
    fun `pogingen boven max levert null`() {
        val volgende = RetryBeleid.volgendePoging(
            nu = nu,
            pogingenNaFout = 100,
            maxPogingen = 5,
            basis = basis,
            cap = cap,
            claimId = 0L,
        )
        assertNull(volgende)
    }

    @Test
    fun `cap voorkomt runaway backoff bij hoge pogingen`() {
        // pogingen=20, basis=1s → 2^20 s = ~12 dagen zonder cap. Cap=1u moet dat
        // beperken tot 1u + jitter (max 25% = 15min).
        val volgende = RetryBeleid.volgendePoging(
            nu = nu,
            pogingenNaFout = 20,
            maxPogingen = 100,
            basis = basis,
            cap = cap,
            claimId = 0L,
        )
        assertNotNull(volgende)
        val vertragingMs = Duration.between(nu, volgende).toMillis()
        assertTrue(
            vertragingMs <= cap.toMillis() + (cap.toMillis() / 4),
            "verwacht <= cap+25%%, kreeg $vertragingMs ms",
        )
    }

    @Test
    fun `verschillende claimIds geven andere retry-tijden voor jitter-spreiding`() {
        // Borgt dat thundering herd voorkomen wordt: twee claims met dezelfde
        // pogingen-count én geclaimd op dezelfde tick krijgen verschillende
        // retry-tijden zolang hun claimId verschilt.
        val a = RetryBeleid.volgendePoging(nu, 2, 5, basis, cap, claimId = 7L)!!
        val b = RetryBeleid.volgendePoging(nu, 2, 5, basis, cap, claimId = 77L)!!
        assertTrue(a != b, "zelfde retry-tijd voor verschillende claimIds = geen jitter-spreiding")
    }

    @Test
    fun `niet herstelbaar levert null voor max bereikt`() {
        val volgende = RetryBeleid.volgendePoging(
            nu = nu,
            pogingenNaFout = 1,
            maxPogingen = 5,
            basis = basis,
            cap = cap,
            claimId = 1L,
            herstelbaar = false,
        )
        assertNull(volgende, "niet-herstelbare fout (bv. 4xx, ConfiguratieFout) moet direct MISLUKT geven")
    }

    @Test
    fun `retryAfter wordt gerespecteerd boven exponentiele backoff`() {
        val hint = Duration.ofSeconds(30)
        val volgende = RetryBeleid.volgendePoging(
            nu = nu,
            pogingenNaFout = 1,
            maxPogingen = 5,
            basis = basis,
            cap = cap,
            claimId = 1L,
            retryAfter = hint,
        )!!
        // Server zegt 30s; exponentieel zou 2s zijn. Server-hint wint.
        assertEquals(nu.plus(hint), volgende)
    }

    @Test
    fun `retryAfter wordt gecaped op cap om runaway te voorkomen`() {
        val absurdLangeHint = Duration.ofDays(7)
        val volgende = RetryBeleid.volgendePoging(
            nu = nu,
            pogingenNaFout = 1,
            maxPogingen = 5,
            basis = basis,
            cap = cap,
            claimId = 1L,
            retryAfter = absurdLangeHint,
        )!!
        assertEquals(nu.plus(cap), volgende)
    }

    @Test
    fun `negatieve pogingenNaFout faalt`() {
        val ex = assertThrows<IllegalArgumentException> {
            RetryBeleid.volgendePoging(nu, -1, 5, basis, cap, 0L)
        }
        assertTrue(ex.message!!.contains("pogingenNaFout"))
    }

    @Test
    fun `negatieve claimId is OK voor jitter (defensive Math floorMod)`() {
        // Geen exception; floorMod hanteert negatieve waardes correct.
        val volgende = RetryBeleid.volgendePoging(nu, 2, 5, basis, cap, claimId = -99L)
        assertNotNull(volgende)
    }
}
