package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.util.UUID

/**
 * Sessieregister voor de profielen zonder echte Redis: elke sessie loopt, zodat die tests over het
 * ophalen gaan en niet over de sessie.
 */
@Alternative
@ApplicationScoped
internal class MockSessieRegister : SessieRegister {

    override fun start(ontvanger: Identificatienummer): Uni<SessieStart> =
        Uni.createFrom().item(SessieStart(UUID.randomUUID().toString()))

    override fun lees(sessieId: String, ontvanger: Identificatienummer): Uni<Boolean> = Uni.createFrom().item(true)

    override fun beeindig(sessieId: String, ontvanger: Identificatienummer): Uni<SessieEinde> =
        Uni.createFrom().item(SessieEinde.BEEINDIGD)
}
