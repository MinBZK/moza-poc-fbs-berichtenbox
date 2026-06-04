package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException

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
    private val ttlSeconden = config.deduplicatie().ttl().seconds

    override fun eerstgezien(eventId: String): Boolean {
        val args = SetArgs().nx().ex(ttlSeconden)

        return runOrServiceUnavailable("markeren") {
            values.setAndChanged(sleutel(eventId), "1", args).await().atMost(TIMEOUT)
        }
    }

    override fun verwijder(eventId: String) {
        runOrServiceUnavailable("verwijderen") {
            keys.del(sleutel(eventId)).await().atMost(TIMEOUT)
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
        val melding = if (oorzaak is TimeoutException) "timeout" else oorzaak.javaClass.simpleName
        log.warnf("Aanmeld-deduplicatie (%s) faalde: %s; 503 naar caller", actie, melding)

        return WebApplicationException("Idempotentie-store niet bereikbaar. Probeer het later opnieuw.", 503)
    }

    private fun sleutel(eventId: String): String = "$KEY_PREFIX$eventId"

    companion object {
        private const val KEY_PREFIX = "aanmeld:event:"
        private val TIMEOUT: Duration = Duration.ofSeconds(2)
    }
}
