package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.TimeoutException as MutinyTimeoutException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import org.jboss.logging.Logger
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException as JucTimeoutException

/**
 * Redis-implementatie van [AanmeldDeduplicatie]. Gebruikt `SET key 1 NX EX <ttl>`:
 * één atomair commando dat alleen schrijft als de sleutel nog niet bestaat — geen
 * venster waarin de marker zonder TTL bestaat. Eigen keyspace (`aanmeld:event:`),
 * los van de berichten-cache.
 *
 * Een onbereikbare Redis levert een 503 op (transient): de webhook geeft dan een
 * 5xx terug zodat de publicatie-stream opnieuw aflevert.
 */
@ApplicationScoped
class RedisAanmeldDeduplicatie(
    redis: ReactiveRedisDataSource,
    config: AanmeldConfig,
) : AanmeldDeduplicatie {

    private val log = Logger.getLogger(RedisAanmeldDeduplicatie::class.java)
    private val values = redis.value(String::class.java)
    private val keys = redis.key(String::class.java)
    // Een TTL van 0 (of negatief) zou idempotentie stil uitschakelen (marker verloopt
    // direct); faal liever luid bij start dan dubbele berichten in productie te krijgen.
    private val ttlSeconden = config.deduplicatie().ttl().seconds
        .also { require(it > 0) { "aanmeld.deduplicatie.ttl moet groter dan nul zijn" } }

    // Een await van 0 (of negatief) maakt `atMost(ZERO)` onbegrensd: een hangende Redis
    // zou de webhook-thread blokkeren i.p.v. fail-fast naar 503. Faal luid bij start.
    private val awaitTimeout = config.deduplicatie().redisAwaitTimeout()
        .also { require(!it.isZero && !it.isNegative) { "aanmeld.deduplicatie.redis-await-timeout moet groter dan nul zijn" } }

    override fun eerstgezien(eventId: String): Boolean {
        val args = SetArgs().nx().ex(ttlSeconden)

        // setAndChanged (niet set): geeft true als de NX-write daadwerkelijk plaatsvond
        // = eerste keer. Een gewone set zou de first-seen-detectie stil breken.
        return runOrServiceUnavailable("markeren") {
            values.setAndChanged(sleutel(eventId), "1", args).await().atMost(awaitTimeout)
        }
    }

    override fun verwijder(eventId: String) {
        runOrServiceUnavailable("verwijderen") {
            keys.del(sleutel(eventId)).await().atMost(awaitTimeout)
        }
    }

    private fun <T> runOrServiceUnavailable(actie: String, block: () -> T): T {
        try {
            return block()
        } catch (e: CompletionException) {
            throw vertaal(actie, e.cause ?: e)
        } catch (e: Exception) {
            throw vertaal(actie, e)
        }
    }

    private fun vertaal(actie: String, oorzaak: Throwable): WebApplicationException {
        val isTimeout = oorzaak is MutinyTimeoutException || oorzaak is JucTimeoutException
        val melding = if (isTimeout) "timeout" else oorzaak.javaClass.simpleName
        log.warnf(oorzaak, "Aanmeld-deduplicatie (%s) faalde: %s; 503 naar caller", actie, melding)

        return WebApplicationException("Idempotentie-store niet bereikbaar. Probeer het later opnieuw.", 503)
    }

    private fun sleutel(eventId: String): String = "$KEY_PREFIX$eventId"

    companion object {
        private const val KEY_PREFIX = "aanmeld:event:"
    }
}
