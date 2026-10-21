package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

/**
 * De Lua-scripts van het sessieregister. Starten, aanraken en beëindigen moeten elk in één atomaire
 * stap beslissen of er nog een lopende sessie is en wat er dan met de gegevens gebeurt; een losse
 * reeks commando's laat twee pods tegelijk "ik ben de laatste" concluderen.
 *
 * Alle scripts delen dezelfde KEYS (zie [RedisSessieRegister.sleutels]):
 * 1. `$k:sessies` — ZSET, lid `sha256(sessie-id)`, score het verloop in ms;
 * 2. `$k:sessies:plafond` — HASH, lid → plafond: start + maximale duur in ms;
 * 3. de stelselbrede verloopindex — ZSET, lid de ontvanger, score zijn laatste verloop in ms;
 * 4–6. de gegevens: `$k:list`, `$k:status`, `$k:lock`.
 *
 * De tijd komt uit Redis (`TIME`) en niet uit de pod: pods met een verschillende klok zouden
 * anders over hetzelfde verloop verschillend oordelen.
 */
internal object SessieScripts {

    /**
     * Gedeelde functies. `purge` is de enige plek die weet welke gegevens bij een ontvanger horen;
     * hij geeft het aantal gewiste gegevenssleutels terug, de sessie-administratie niet meegeteld.
     */
    private val GEDEELD = """
        local function tijdInMs()
            local t = redis.call('TIME')
            return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
        end

        local function ruimVerlopenOp(nu)
            local verlopen = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', nu)
            if #verlopen > 0 then
                redis.call('ZREM', KEYS[1], unpack(verlopen))
                redis.call('HDEL', KEYS[2], unpack(verlopen))
            end
        end

        local function purge()
            local gewist = redis.call('DEL', KEYS[4], KEYS[5], KEYS[6])
            redis.call('DEL', KEYS[1], KEYS[2])
            return gewist
        end
    """.trimIndent()

    /**
     * ARGV: sessie-hash, inactiviteit (ms), maximale duur (ms), grens per ontvanger, ontvanger,
     * vangnet-TTL van de sessie-administratie (ms).
     *
     * Antwoord `{0}` bij de grens, anders `{1, verloop in ms, aantal gewiste gegevenssleutels}`.
     * Alleen een start zonder andere lopende sessie wist: die begint met een vers beeld.
     */
    val START = GEDEELD + "\n\n" + """
        local nu = tijdInMs()
        ruimVerlopenOp(nu)

        local gewist = 0
        if redis.call('ZCARD', KEYS[1]) == 0 then
            gewist = purge()
        end

        if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[4]) then
            return { 0 }
        end

        local plafond = nu + tonumber(ARGV[3])
        local verloop = math.min(nu + tonumber(ARGV[2]), plafond)
        redis.call('ZADD', KEYS[1], verloop, ARGV[1])
        redis.call('HSET', KEYS[2], ARGV[1], plafond)
        redis.call('PEXPIRE', KEYS[1], ARGV[6])
        redis.call('PEXPIRE', KEYS[2], ARGV[6])
        redis.call('ZADD', KEYS[3], 'GT', verloop, ARGV[5])
        return { 1, verloop, gewist }
    """.trimIndent()

    /**
     * ARGV: sessie-hash, inactiviteit (ms), ontvanger. Antwoord 1 als de sessie loopt en verlengd
     * is, anders 0. Het verloop schuift nooit voorbij start + maximale duur.
     */
    val RAAK = GEDEELD + "\n\n" + """
        local nu = tijdInMs()
        local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
        if not score or tonumber(score) <= nu then
            return 0
        end

        local plafond = tonumber(redis.call('HGET', KEYS[2], ARGV[1]))
        if not plafond then
            return 0
        end

        local verloop = math.min(nu + tonumber(ARGV[2]), plafond)
        redis.call('ZADD', KEYS[1], verloop, ARGV[1])
        redis.call('ZADD', KEYS[3], 'GT', verloop, ARGV[3])
        return 1
    """.trimIndent()

    /**
     * ARGV: sessie-hash, ontvanger. Antwoord `ONBEKEND`, `BEEINDIGD` of `OPGERUIMD` (zie
     * [SessieEinde]).
     *
     * Blijft er geen lopende sessie over, dan wist het script ook bij een onbekend id: er is dan
     * niemand meer voor wie de gegevens bestaan. Wist dat iets, dan is de uitkomst `OPGERUIMD`,
     * zodat het wissen in het logboek komt.
     */
    val EINDE = GEDEELD + "\n\n" + """
        local nu = tijdInMs()
        local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
        local bekend = score and tonumber(score) > nu
        redis.call('ZREM', KEYS[1], ARGV[1])
        redis.call('HDEL', KEYS[2], ARGV[1])
        ruimVerlopenOp(nu)

        if redis.call('ZCARD', KEYS[1]) > 0 then
            -- Zonder GT: de beëindigde sessie kan de laatst verlopende geweest zijn, en een te late
            -- score laat de veegtaak deze ontvanger tot een inactiviteitsduur te laat vinden.
            local laatste = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
            redis.call('ZADD', KEYS[3], laatste[2], ARGV[2])
            if bekend then
                return 'BEEINDIGD'
            end
            return 'ONBEKEND'
        end

        local gewist = purge()
        redis.call('ZREM', KEYS[3], ARGV[2])
        if bekend or gewist > 0 then
            return 'OPGERUIMD'
        end
        return 'ONBEKEND'
    """.trimIndent()
}
