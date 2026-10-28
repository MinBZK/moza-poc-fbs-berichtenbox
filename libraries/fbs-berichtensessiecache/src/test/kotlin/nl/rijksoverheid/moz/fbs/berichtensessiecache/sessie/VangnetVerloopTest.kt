package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenCache
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * Het vangnet zelf, tegen een echte Redis met een maximale duur van drie seconden uit
 * [KortVangnetRedisTestProfile]: wat er gebeurt als niemand uitlogt.
 */
@QuarkusTest
@TestProfile(KortVangnetRedisTestProfile::class)
class VangnetVerloopTest {

    @Inject
    lateinit var sessiecache: Sessiecache

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @Test
    fun `zonder uitloggen verloopt de sessie na de maximale duur`() {
        val bsn = Bsn("999993653")
        val k = BerichtenCache.cacheKey(bsn)
        redis.key().del("$k:sessies").await().atMost(WACHTTIJD)
        val sessieId = sessiecache.startSessie(bsn).sessieId

        assertDoesNotThrow { sessiecache.leesSessie(sessieId, bsn) }

        Thread.sleep(MAX_DUUR_MS + MARGE_MS)

        assertFalse(redis.key().exists("$k:sessies").await().atMost(WACHTTIJD))
        assertThrows<SessiecacheException.SessieOngeldig> { sessiecache.leesSessie(sessieId, bsn) }
    }

    private companion object {
        val WACHTTIJD: Duration = Duration.ofSeconds(5)

        // De maximale duur uit het profiel; de marge hierna dekt de traagheid van de test zelf.
        const val MAX_DUUR_MS = 3_000L
        const val MARGE_MS = 700L
    }
}
