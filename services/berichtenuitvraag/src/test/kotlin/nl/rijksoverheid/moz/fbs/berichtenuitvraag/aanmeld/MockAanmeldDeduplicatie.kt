package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import io.quarkus.test.junit.QuarkusTestProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecache
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [AanmeldDeduplicatie] voor de uitvraag-testsuite: echte first-seen-
 * semantiek zonder Redis, plus fout-injectie. De Redis-implementatie krijgt een
 * eigen integratietest met een echte backing store.
 */
@Alternative
@ApplicationScoped
class MockAanmeldDeduplicatie : AanmeldDeduplicatie {

    private val gezien = ConcurrentHashMap.newKeySet<String>()
    val verwijderd = mutableListOf<String>()
    var fout: RuntimeException? = null

    fun reset() {
        gezien.clear()
        verwijderd.clear()
        fout = null
    }

    override fun eerstgezien(eventId: String): Boolean {
        fout?.let { throw it }

        return gezien.add(eventId)
    }

    override fun verwijder(eventId: String) {
        verwijderd += eventId
        gezien.remove(eventId)
    }
}

/**
 * Activeert de in-memory facade- én deduplicatie-alternatives, zodat de aanmeld-
 * webhook end-to-end testbaar is zonder Redis. Spiegelt de bean-samenstelling van
 * [nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecacheProfile].
 */
class AanmeldMockProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> =
        setOf(MockSessiecache::class.java, MockAanmeldDeduplicatie::class.java)

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.arc.unremovable-types" to "nl.rijksoverheid.moz.fbs.berichtensessiecache.**",
        "quarkus.redis.hosts" to "redis://localhost:6379",
    )
}
