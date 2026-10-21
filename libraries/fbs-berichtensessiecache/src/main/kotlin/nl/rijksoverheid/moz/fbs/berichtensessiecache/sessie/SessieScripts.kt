package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

/**
 * De Lua-scripts van het sessieregister. Starten, aanraken en beëindigen moeten elk in één atomaire
 * stap beslissen of er nog een lopende sessie is en wat er dan met de gegevens gebeurt; een losse
 * reeks commando's laat twee pods tegelijk "ik ben de laatste" concluderen.
 *
 * Alle scripts behalve [KANDIDATEN] delen dezelfde KEYS (zie [RedisSessieRegister.sleutels]):
 * 1. `$k:sessies` — ZSET, lid `sha256(sessie-id)`, score het verloop in ms;
 * 2. `$k:sessies:plafond` — HASH, lid → plafond: start + maximale duur in ms;
 * 3. de stelselbrede verloopindex — ZSET, lid de ontvanger, score zijn laatste verloop in ms;
 * 4–6. de gegevens onder een vaste naam: `$k:list`, `$k:status`, `$k:lock`;
 * 7. `$k:sleutels` — SET met elke andere gegevenssleutel van de ontvanger: de berichthashes, ook
 *    die van eerdere ophaalrondes, en de tombstones. De hashes vallen buiten de sessie-prefix en
 *    zijn anders alleen via een zoekopdracht te vinden.
 *
 * Omdat de leden van `$k:sleutels` niet in KEYS staan, gaan deze scripts uit van één Redis-node,
 * zoals het aanmeldscript van de berichtencache.
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

        -- In stukken: unpack van duizenden leden loopt tegen de stackgrens van Lua aan.
        local function purge()
            local gewist = redis.call('DEL', KEYS[4], KEYS[5], KEYS[6])
            local leden = redis.call('SMEMBERS', KEYS[7])
            for i = 1, #leden, 1000 do
                gewist = gewist + redis.call('DEL', unpack(leden, i, math.min(i + 999, #leden)))
            end
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[7])
            return gewist
        end

        -- Een BEZIG-status houdt zijn korte TTL: die moet na een gecrashte ophaalronde snel vrijkomen.
        local function trekTtlOp(ttl)
            redis.call('PEXPIRE', KEYS[4], ttl)
            for _, sleutel in ipairs(redis.call('SMEMBERS', KEYS[7])) do
                redis.call('PEXPIRE', sleutel, ttl)
            end
            redis.call('PEXPIRE', KEYS[7], ttl)

            local status = redis.call('GET', KEYS[5])
            if status then
                local leesbaar, waarde = pcall(cjson.decode, status)
                if leesbaar and type(waarde) == 'table' and waarde.status ~= 'BEZIG' then
                    redis.call('PEXPIRE', KEYS[5], ttl)
                end
            end
        end
    """.trimIndent()

    /**
     * ARGV: sessie-hash, inactiviteit (ms), maximale duur (ms), grens per ontvanger, ontvanger,
     * vangnet-TTL van de sessie-administratie en de gegevens (ms).
     *
     * Antwoord `{0}` bij de grens, anders `{1, verloop in ms, aantal gewiste gegevenssleutels}`.
     * Alleen een start zonder andere lopende sessie wist: die begint met een vers beeld. Een start
     * naast een lopende sessie trekt de TTL van de bestaande gegevens op, anders verlopen ze vóór
     * het plafond van de nieuwe sessie.
     */
    val START = GEDEELD + "\n\n" + """
        local nu = tijdInMs()
        ruimVerlopenOp(nu)

        local leeg = redis.call('ZCARD', KEYS[1]) == 0
        local gewist = 0
        if leeg then
            gewist = purge()
        end

        if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[4]) then
            return { 0 }
        end

        if not leeg then
            trekTtlOp(ARGV[6])
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

    /**
     * Geen ARGV. Antwoord 1 als er geen lopende sessie is en er gegevens gewist zijn, anders 0.
     *
     * Draait ná het schrijven van een ophaalronde of een aanmelding: eindigde de laatste sessie
     * terwijl die liep, dan ruimt dit het geschrevene op, in welke volgorde het ook gebeurde.
     * De verloopindex blijft ongemoeid; die is van de veegtaak, en deze ontvanger staat er hooguit
     * nog in als zijn laatste sessie verliep zonder dat er geveegd is.
     */
    val OPRUIMEN = GEDEELD + "\n\n" + """
        ruimVerlopenOp(tijdInMs())

        if redis.call('ZCARD', KEYS[1]) > 0 then
            return 0
        end

        if purge() > 0 then
            return 1
        end
        return 0
    """.trimIndent()

    /**
     * Afwijkende KEYS: alleen de verloopindex. ARGV: het maximum aantal. Antwoord de ontvangers wier
     * laatste sessie volgens de index verlopen is. Een script en geen losse `ZRANGEBYSCORE`, zodat
     * "nu" dezelfde Redis-tijd is als in [VEEG].
     */
    val KANDIDATEN = GEDEELD + "\n\n" + """
        return redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', tijdInMs(), 'LIMIT', 0, tonumber(ARGV[1]))
    """.trimIndent()

    /**
     * ARGV: ontvanger. Antwoord 1 als dit script de ontvanger heeft opgeruimd, anders 0.
     *
     * De index is alleen een aanwijzing: tussen [KANDIDATEN] en dit script kan een sessie gestart of
     * geraakt zijn, of een andere pod al geveegd hebben. Pas als hier blijkt dat er echt geen lopende
     * sessie meer is, wordt er gewist en verdwijnt de ontvanger uit de index; zo ruimt precies één
     * pod op en meldt het. Ook als een eerder opruimen na een schrijfactie de gegevens al wiste, is
     * dit het moment dat de verwerking eindigt, dus dan is het antwoord eveneens 1.
     */
    val VEEG = GEDEELD + "\n\n" + """
        local nu = tijdInMs()
        local score = redis.call('ZSCORE', KEYS[3], ARGV[1])
        if not score or tonumber(score) > nu then
            return 0
        end

        ruimVerlopenOp(nu)

        if redis.call('ZCARD', KEYS[1]) > 0 then
            -- Zonder GT: de index liep achter op een lopende sessie en moet naar diens verloop.
            local laatste = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
            redis.call('ZADD', KEYS[3], laatste[2], ARGV[1])
            return 0
        end

        purge()
        redis.call('ZREM', KEYS[3], ARGV[1])
        return 1
    """.trimIndent()
}
