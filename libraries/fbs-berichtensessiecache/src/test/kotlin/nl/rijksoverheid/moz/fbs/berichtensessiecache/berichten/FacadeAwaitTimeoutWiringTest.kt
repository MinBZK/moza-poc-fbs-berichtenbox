package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Pin dat `berichtensessiecache.facade-await-timeout-seconds` daadwerkelijk de
 * blocking-await-grens van [nl.rijksoverheid.moz.fbs.berichtensessiecache.BlockingSessiecache]
 * bedient. Een verkeerd gespelde property-naam of ontbrekende `@ConfigProperty` zou compileren
 * (de unit-test geeft de waarde als literal door) en daarna stil terugvallen op de default 5s
 * in productie.
 *
 * De gereed-status-lookup wordt 3s vertraagd; de override zet de grens op 1s. 3s ligt tússen de
 * geconfigureerde 1s en de default 5s: alleen als de property gelezen wordt slaat de facade-await
 * aan (→ [SessiecacheException.Onbereikbaar]). Bij een losgekoppelde property zou de default 5s de
 * 3s-delay opvangen en zou er geen fout volgen.
 */
@QuarkusTest
@TestProfile(FacadeAwaitTimeoutTestProfile::class)
class FacadeAwaitTimeoutWiringTest {

    @Inject
    lateinit var sessiecache: Sessiecache

    @Test
    fun `facade-await-timeout uit config bedient de blocking-await-grens`() {
        val ex = assertThrows(SessiecacheException.Onbereikbaar::class.java) {
            sessiecache.lijst(Bsn("999993653"))
        }

        assertTrue(ex.message!!.contains("bereikbaar", ignoreCase = true), "Was: ${ex.message}")
    }
}

/**
 * Vertraagt uitsluitend de gereed-status-lookup zodat de facade-await meetbaar wordt; alle
 * overige cache-operaties erven het directe in-memory-gedrag van [MockBerichtenCache].
 */
@Alternative
@ApplicationScoped
internal class TraagStatusBerichtenCache : MockBerichtenCache() {

    override fun getAggregationStatus(key: String): Uni<AggregationStatus?> =
        super.getAggregationStatus(key).onItem().delayIt().by(Duration.ofSeconds(3))
}

class FacadeAwaitTimeoutTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        TraagStatusBerichtenCache::class.java,
        MockMagazijnClientFactory::class.java,
        MockMagazijnResolver::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "false",
        "quarkus.redis.hosts" to "redis://localhost:6379",
        "berichtensessiecache.facade-await-timeout-seconds" to "1",
    )
}
