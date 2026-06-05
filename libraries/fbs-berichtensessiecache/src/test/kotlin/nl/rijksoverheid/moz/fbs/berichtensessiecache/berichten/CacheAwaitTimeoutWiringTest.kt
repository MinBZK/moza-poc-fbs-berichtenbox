package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResolver
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Pin dat `berichtensessiecache.cache-await-timeout-seconds` daadwerkelijk de
 * Redis-await-grens in [BerichtensessiecacheService.haalBerichtenOp] bedient. Een
 * verkeerd gespelde property-naam of ontbrekende `@ConfigProperty` zou compileren én
 * alle unit-tests halen (die de waarde als literal doorgeven) en daarna stil
 * terugvallen op de default in productie.
 *
 * De lock-acquire wordt 3s vertraagd; de override zet de grens op 1s. 3s ligt tússen
 * de geconfigureerde 1s en de default 5s: alleen als de property gelezen wordt slaat
 * de await-timeout aan (→ 503). Bij een losgekoppelde property zou de default 5s de
 * 3s-delay opvangen en zou er geen fout volgen.
 */
@QuarkusTest
@TestProfile(CacheAwaitTimeoutTestProfile::class)
class CacheAwaitTimeoutWiringTest {

    @Inject
    internal lateinit var service: BerichtensessiecacheService

    @Test
    fun `cache-await-timeout uit config bedient de Redis-await-grens`() {
        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBerichtenOp(Bsn("999993653"))
        }

        assertEquals(503, ex.response.status)
        assertTrue(ex.message!!.contains("timeout", ignoreCase = true), "Was: ${ex.message}")
    }
}

/**
 * Vertraagt uitsluitend de lock-acquire zodat de await-timeout meetbaar wordt; alle
 * overige cache-operaties erven het directe in-memory-gedrag van [MockBerichtenCache].
 */
@Alternative
@ApplicationScoped
internal class TraagLockBerichtenCache : MockBerichtenCache() {

    override fun trySetAggregationStatus(key: String, status: AggregationStatus): Uni<Boolean> =
        super.trySetAggregationStatus(key, status).onItem().delayIt().by(Duration.ofSeconds(3))
}

class CacheAwaitTimeoutTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        TraagLockBerichtenCache::class.java,
        MockMagazijnClientFactory::class.java,
        MockMagazijnResolver::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "false",
        "quarkus.redis.hosts" to "redis://localhost:6379",
        "berichtensessiecache.cache-await-timeout-seconds" to "1",
    )
}
