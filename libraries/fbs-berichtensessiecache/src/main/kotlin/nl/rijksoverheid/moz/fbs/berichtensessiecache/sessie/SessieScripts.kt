package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

/**
 * De Lua-scripts van het sessieregister. Elk script is één atomaire stap: een start zet de sessie
 * en de TTL samen, zodat er nooit een sessie zonder TTL blijft staan.
 *
 * Alle scripts delen dezelfde KEYS (zie [RedisSessieRegister.sleutels]):
 * 1. `$k:sessies` — ZSET, lid `sha256(sessie-id)`, score het startmoment in ms.
 *
 * Een sessie verloopt niet uit zichzelf: ze loopt tot het uitloggen, of tot de TTL op
 * `$k:sessies` ([SessieConfig.maxDuur] na de laatste start) de administratie laat verdwijnen.
 *
 * De tijd komt uit Redis (`TIME`) en niet uit de pod: pods met een verschillende klok zouden
 * anders verschillende startmomenten noteren.
 */
internal object SessieScripts {

    /** Gedeelde functies. */
    private val GEDEELD = """
        local function tijdInMs()
            local t = redis.call('TIME')
            return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
        end
    """.trimIndent()

    /**
     * ARGV: sessie-hash, TTL van de sessie-administratie (ms).
     *
     * Antwoord `{1}`.
     */
    val START = GEDEELD + "\n\n" + """
        redis.call('ZADD', KEYS[1], tijdInMs(), ARGV[1])
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
        return { 1 }
    """.trimIndent()

    /** ARGV: sessie-hash. Antwoord 1 als de sessie loopt, anders 0. */
    val LEES = GEDEELD + "\n\n" + """
        if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
            return 1
        end
        return 0
    """.trimIndent()

    /** ARGV: sessie-hash. Antwoord `ONBEKEND` of `BEEINDIGD` (zie [SessieEinde]). */
    val EINDE = GEDEELD + "\n\n" + """
        local bekend = redis.call('ZREM', KEYS[1], ARGV[1]) == 1

        if bekend then
            return 'BEEINDIGD'
        end
        return 'ONBEKEND'
    """.trimIndent()
}
