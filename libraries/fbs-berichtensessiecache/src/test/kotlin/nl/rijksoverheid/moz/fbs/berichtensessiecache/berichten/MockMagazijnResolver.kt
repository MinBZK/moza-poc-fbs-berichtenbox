package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResolver
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer

/**
 * In-memory alternatieve implementatie van [MagazijnResolver] voor tests met
 * [MockedDependenciesProfile]. Retourneert altijd de volledige set geconfigureerde
 * magazijn-IDs zodat bestaande ophaal-tests hun aggregatiegedrag ongewijzigd testen
 * zonder afhankelijkheid van de echte [nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.ProfielMagazijnResolver].
 */
@Alternative
@ApplicationScoped
internal class MockMagazijnResolver : MagazijnResolver {

    override fun resolve(ontvanger: Identificatienummer): Uni<Set<String>> =
        Uni.createFrom().item(setOf("magazijn-a", "magazijn-b"))
}
