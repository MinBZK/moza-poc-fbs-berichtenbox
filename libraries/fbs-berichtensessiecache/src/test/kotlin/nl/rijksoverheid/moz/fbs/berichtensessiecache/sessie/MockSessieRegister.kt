package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.time.Instant
import java.util.UUID

/**
 * Sessieregister voor de profielen zonder echte Redis. Daar draait een ophaalronde of aanmelding
 * zonder voorafgaande sessie; dit register gedraagt zich alsof er altijd een loopt, zodat die
 * tests over het ophalen gaan en niet over het opruimen erna.
 */
@Alternative
@ApplicationScoped
internal class MockSessieRegister : SessieRegister {

    override fun start(ontvanger: Identificatienummer): Uni<SessieStart?> =
        Uni.createFrom().item(SessieStart(UUID.randomUUID().toString(), Instant.now().plusSeconds(900), false))

    override fun raak(sessieId: String, ontvanger: Identificatienummer): Uni<Boolean> = Uni.createFrom().item(true)

    override fun beeindig(sessieId: String, ontvanger: Identificatienummer): Uni<SessieEinde> =
        Uni.createFrom().item(SessieEinde.BEEINDIGD)

    override fun ruimOpZonderSessie(cacheKey: String): Uni<Boolean> = Uni.createFrom().item(false)
}
