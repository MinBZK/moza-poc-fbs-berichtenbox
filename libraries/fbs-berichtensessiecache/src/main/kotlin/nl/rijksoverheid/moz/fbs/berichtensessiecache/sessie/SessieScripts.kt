package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

/**
 * De Lua-scripts van het sessieregister. Starten en beëindigen moeten elk in één atomaire stap
 * beslissen of er nog een lopende sessie is en wat er dan met de gegevens gebeurt; een losse
 * reeks commando's laat twee pods tegelijk "ik ben de laatste" concluderen.
 *
 * Alle scripts delen dezelfde KEYS (zie [RedisSessieRegister.sleutels]):
 * 1. `$k:sessies` — ZSET, lid `sha256(sessie-id)`, score het startmoment in ms;
 * 2–4. de gegevens onder een vaste naam: `$k:list`, `$k:status`, `$k:lock`;
 * 5. `$k:sleutels` — SET met elke andere gegevenssleutel van de ontvanger: de berichthashes, ook
 *    die van eerdere ophaalrondes, en de tombstones. De hashes vallen buiten de sessie-prefix en
 *    zijn anders alleen via een zoekopdracht te vinden.
 *
 * Een sessie verloopt niet uit zichzelf: ze loopt tot het uitloggen, of tot de vangnet-TTL op
 * `$k:sessies` (zie [dataTtl]) de administratie laat verdwijnen.
 *
 * Omdat de leden van `$k:sleutels` niet in KEYS staan, gaan deze scripts uit van één Redis-node,
 * zoals het aanmeldscript van de berichtencache.
 *
 * De tijd komt uit Redis (`TIME`) en niet uit de pod: pods met een verschillende klok zouden
 * anders verschillende startmomenten noteren.
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

        -- In stukken: unpack van duizenden leden loopt tegen de stackgrens van Lua aan.
        local function purge()
            local gewist = redis.call('DEL', KEYS[2], KEYS[3], KEYS[4])
            local leden = redis.call('SMEMBERS', KEYS[5])
            for i = 1, #leden, 1000 do
                gewist = gewist + redis.call('DEL', unpack(leden, i, math.min(i + 999, #leden)))
            end
            redis.call('DEL', KEYS[1], KEYS[5])
            return gewist
        end

        -- Een BEZIG-status houdt zijn korte TTL: die moet na een gecrashte ophaalronde snel vrijkomen.
        local function trekTtlOp(ttl)
            redis.call('PEXPIRE', KEYS[2], ttl)
            for _, sleutel in ipairs(redis.call('SMEMBERS', KEYS[5])) do
                redis.call('PEXPIRE', sleutel, ttl)
            end
            redis.call('PEXPIRE', KEYS[5], ttl)

            local status = redis.call('GET', KEYS[3])
            if status then
                local leesbaar, waarde = pcall(cjson.decode, status)
                if leesbaar and type(waarde) == 'table' and waarde.status ~= 'BEZIG' then
                    redis.call('PEXPIRE', KEYS[3], ttl)
                end
            end
        end
    """.trimIndent()

    /**
     * ARGV: sessie-hash, vangnet-TTL van de sessie-administratie en de gegevens (ms).
     *
     * Antwoord `{1, opgeruimd}`, met opgeruimd 1 als de start gegevens wiste, anders 0.
     *
     * Alleen een start zonder andere lopende sessie wist: die begint met een vers beeld. Een start
     * naast een lopende sessie trekt de TTL van de bestaande gegevens op, anders kan de vangnet-TTL
     * ze wissen terwijl de nieuwe sessie nog loopt.
     */
    val START = GEDEELD + "\n\n" + """
        local leeg = redis.call('ZCARD', KEYS[1]) == 0
        local opgeruimd = 0
        if leeg then
            if purge() > 0 then
                opgeruimd = 1
            end
        end

        if not leeg then
            trekTtlOp(ARGV[2])
        end

        redis.call('ZADD', KEYS[1], tijdInMs(), ARGV[1])
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
        return { 1, opgeruimd }
    """.trimIndent()

    /** ARGV: sessie-hash. Antwoord 1 als de sessie loopt, anders 0. */
    val LEES = GEDEELD + "\n\n" + """
        if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
            return 1
        end
        return 0
    """.trimIndent()

    /**
     * ARGV: sessie-hash. Antwoord `ONBEKEND`, `BEEINDIGD` of `OPGERUIMD` (zie [SessieEinde]).
     *
     * Blijft er geen lopende sessie over, dan wist het script ook bij een onbekend id: er is dan
     * niemand meer voor wie de gegevens bestaan. Wist dat iets, dan is de uitkomst `OPGERUIMD`,
     * zodat het wissen in het logboek komt.
     */
    val EINDE = GEDEELD + "\n\n" + """
        local bekend = redis.call('ZREM', KEYS[1], ARGV[1]) == 1

        if redis.call('ZCARD', KEYS[1]) > 0 then
            if bekend then
                return 'BEEINDIGD'
            end
            return 'ONBEKEND'
        end

        local gewist = purge()
        if bekend or gewist > 0 then
            return 'OPGERUIMD'
        end
        return 'ONBEKEND'
    """.trimIndent()

    /**
     * Geen ARGV. Antwoord 1 als er geen lopende sessie is en er gegevens gewist zijn, anders 0.
     *
     * Draait ná het schrijven van een ophaalronde of een aanmelding: eindigde de laatste sessie
     * terwijl die liep, dan ruimt dit het geschrevene op, in welke volgorde het ook gebeurde. Dit
     * wissen krijgt geen eigen logregel.
     */
    val OPRUIMEN = GEDEELD + "\n\n" + """
        if redis.call('ZCARD', KEYS[1]) > 0 then
            return 0
        end

        if purge() > 0 then
            return 1
        end
        return 0
    """.trimIndent()
}
