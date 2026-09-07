package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.smallrye.mutiny.Uni

/**
 * Biedt Redis-commando's in batches aan in plaats van allemaal tegelijk.
 *
 * De Vert.x-Redis-client houdt per connection een wachtrij bij van commando's waarvan het antwoord
 * nog moet komen, begrensd door `quarkus.redis.max-waiting-handlers`. Een `Uni.join().all(...)`
 * over een lijst subscribet op alle Unis tegelijk en schrijft dus alle commando's in één keer weg;
 * bij een fan-out die met het aantal organisaties meegroeit loopt die wachtrij vol en faalt de hele
 * keten met "Redis waiting queue is full".
 *
 * Deze helper koppelt het aantal in-flight commando's los van de lijstlengte: een batch wordt pas
 * aangeboden zodra de vorige beantwoord is. Binnen een MULTI/EXEC blijft dat volledig atomair —
 * Redis antwoordt op elk commando in de transactie met `+QUEUED` en voert pas bij EXEC uit, dus
 * batchen begrenst enkel het aanbieden en niet de transactie zelf.
 */
internal object RedisBatching {

    /**
     * Past [commando] toe op elk item in [items], in batches van [batchgrootte]. Binnen een batch
     * lopen de commando's parallel en fail-fast; batches volgen elkaar sequentieel op. Een lege
     * [items] levert een direct voltooide `Uni` zonder Redis te raken.
     *
     * @throws IllegalArgumentException als [batchgrootte] niet groter is dan 0 — bij 0 zou
     *   `chunked` werpen met een melding die de configuratiesleutel niet noemt.
     */
    fun <T> inBatches(items: List<T>, batchgrootte: Int, commando: (T) -> Uni<Void>): Uni<Void> {
        require(batchgrootte > 0) { "batchgrootte ($batchgrootte) moet groter zijn dan 0" }

        if (items.isEmpty()) return Uni.createFrom().voidItem()

        return items.chunked(batchgrootte).fold(Uni.createFrom().voidItem()) { voorgaande, batch ->
            voorgaande.chain { _ ->
                Uni.join().all(batch.map(commando)).andFailFast().replaceWithVoid()
            }
        }
    }
}
