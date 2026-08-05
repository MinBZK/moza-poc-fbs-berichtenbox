package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.quarkus.redis.datasource.RedisDataSource
import jakarta.enterprise.context.ApplicationScoped

/**
 * Laat de sessiecache "verlopen" door alle sessie-keys te wissen. Het verlies van de
 * `:status`-key doet de uitvraag denken dat er geen actieve sessie is → GET /berichten geeft
 * 409. Bewust alleen DEL op de sessie-prefix; nooit FLUSHDB/FT.DROPINDEX (die slopen de index).
 */
@ApplicationScoped
class SessieService(private val redis: RedisDataSource) {

    fun laatSessiesVerlopen(): Int {
        val keys = redis.key().keys(SESSIE_PATROON)

        return if (keys.isEmpty()) 0 else redis.key().del(*keys.toTypedArray())
    }

    private companion object {

        const val SESSIE_PATROON = "berichtensessiecache:v1:*"
    }
}
