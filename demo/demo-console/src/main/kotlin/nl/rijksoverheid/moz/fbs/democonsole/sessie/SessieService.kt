package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.quarkus.redis.datasource.RedisDataSource
import jakarta.enterprise.context.ApplicationScoped

/**
 * Laat de sessiecache "verlopen" door alle sessie-keys te wissen. Zonder de `:status`-key ziet de
 * uitvraag een sessie die nog niet gevuld is (niet: een afwezige sessie), en geeft GET /berichten
 * 409 — de "nog niet opgehaald"-tak, dezelfde als vóór de eerste ophaalronde. Bewust alleen DEL op
 * de sessie-prefix; nooit FLUSHDB/FT.DROPINDEX (die slopen de index).
 */
@ApplicationScoped
class SessieService(private val redis: RedisDataSource) {

    fun laatSessiesVerlopen(): Int {
        val keys = redis.key().keys(SESSIE_PATROON)

        return if (keys.isEmpty()) 0 else redis.key().del(*keys.toTypedArray())
    }

    private companion object {

        // Versieloos patroon: de sessiecache versienummert zijn sleutels (`:v1:`, `:v2:`, …) en
        // die versie bumpt bij elke wijziging van het opslagformaat. Een patroon met een vast
        // versienummer zou na zo'n bump niets meer vinden en tóch "0 sessie-keys gewist" melden —
        // een knop die succes rapporteert zonder iets te doen. De prefix zelf is specifiek genoeg.
        const val SESSIE_PATROON = "berichtensessiecache:*"
    }
}
