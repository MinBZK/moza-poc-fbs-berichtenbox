package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResolver
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.WireMockMagazijnResource
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * In-memory alternatieve implementatie van [MagazijnResolver] voor tests met
 * [MockedDependenciesProfile]. Retourneert altijd de volledige set geconfigureerde
 * magazijn-IDs (de afzender-OINs uit de test-register-config) zodat bestaande
 * ophaal-tests hun aggregatiegedrag ongewijzigd testen zonder afhankelijkheid van
 * de echte [nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.ProfielMagazijnResolver].
 */
@Alternative
@ApplicationScoped
internal class MockMagazijnResolver : MagazijnResolver {

    companion object {
        /** Telt af zodra een ronde de resolver bereikt, dus de ophaal-lock al heeft. */
        @Volatile
        var bereikt: CountDownLatch? = null

        /** Houdt de resolver vast tot deze latch valt. */
        @Volatile
        var tegenhouden: CountDownLatch? = null

        /** Laat de resolver hiermee falen, ná het tegenhouden. */
        @Volatile
        var fout: RuntimeException? = null
    }

    override fun resolve(ontvanger: Identificatienummer): Uni<Set<String>> =
        Uni.createFrom().item {
            bereikt?.countDown()
            tegenhouden?.await(TEGENHOUDEN_MAX_S, TimeUnit.SECONDS)
            fout?.let { throw it }
            setOf(WireMockMagazijnResource.OIN_A, WireMockMagazijnResource.OIN_B)
        }
}

// Onder de outer-await van de resolver, zodat een vergeten latch geen time-out uitlokt.
private const val TEGENHOUDEN_MAX_S = 8L
