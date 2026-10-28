package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

/**
 * De Lua-scripts van het sessieregister. Starten, aanraken en beëindigen moeten elk in één atomaire
 * stap beslissen of er nog een lopende sessie is en wat er dan met de gegevens gebeurt; een losse
 * reeks commando's laat twee pods tegelijk "ik ben de laatste" concluderen.
 *
 * Alle scripts behalve [KANDIDATEN], [UITSTEL] en [MARKEER] delen dezelfde KEYS (zie
 * [RedisSessieRegister.sleutels]):
 * 1. `$k:sessies` — ZSET, lid `sha256(sessie-id)`, score het verloop in ms;
 * 2. `$k:sessies:plafond` — HASH, lid → plafond: start + maximale duur in ms;
 * 3. de stelselbrede verloopindex — ZSET, lid het kenmerk van de ontvanger (de hash uit `$k`),
 *    score zijn laatste verloop in ms;
 * 4–6. de gegevens onder een vaste naam: `$k:list`, `$k:status`, `$k:lock`;
 * 7. `$k:sleutels` — SET met elke andere gegevenssleutel van de ontvanger: de berichthashes, ook
 *    die van eerdere ophaalrondes, en de tombstones. De hashes vallen buiten de sessie-prefix en
 *    zijn anders alleen via een zoekopdracht te vinden;
 * 8. `$k:ontvanger` — STRING, de leesbare ontvanger, voor de logregel van de veegtaak.
 *
 * De index bevat alleen kenmerken: één stelselbrede sleutel hoort geen lijst van BSN's te zijn. De
 * leesbare ontvanger staat per ontvanger in `$k:ontvanger`, met de vangnet-TTL, en gaat weg samen met
 * zijn index-entry. Elk script dat een entry maakt, schrijft hem. Elk script dat de index bijwerkt,
 * zet ook zijn TTL opnieuw (het laatste ARGV, zie [indexTtl]): draait de veegtaak niet meer, dan
 * blijft de index niet onbeperkt staan.
 *
 * Omdat de leden van `$k:sleutels` niet in KEYS staan, gaan deze scripts uit van één Redis-node,
 * zoals het aanmeldscript van de berichtencache.
 *
 * De tijd komt uit Redis (`TIME`) en niet uit de pod: pods met een verschillende klok zouden
 * anders over hetzelfde verloop verschillend oordelen.
 *
 * De verloopindex is ook de takenlijst voor het logboek. Een script dat gegevens wist zonder dat
 * dat zelf tot een logregel leidt, laat de ontvanger met score "nu" in de index staan; wie die
 * entry daarna weghaalt ([VEEG], [EINDE], [START]), meldt de opruiming. Zo komt elk wissen door
 * een van deze scripts in het logboek.
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

        -- Of het kenmerk met een verstreken score in de index staat: een opruiming die nog op de
        -- veegtaak wacht. Wie hem weghaalt, neemt de melding over.
        local function wachtend(kenmerk, nu)
            local score = redis.call('ZSCORE', KEYS[3], kenmerk)
            return score and tonumber(score) <= nu
        end

        -- De leesbare ontvanger hoort bij de index-entry en verdwijnt ermee.
        local function haalUitIndex(kenmerk)
            redis.call('ZREM', KEYS[3], kenmerk)
            redis.call('DEL', KEYS[8])
        end

        -- Na elke bijwerking van de index: zie indexTtl.
        local function houdIndexAan(ttl)
            redis.call('PEXPIRE', KEYS[3], ttl)
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
     * ARGV: sessie-hash, inactiviteit (ms), maximale duur (ms), kenmerk,
     * vangnet-TTL van de sessie-administratie en de gegevens (ms), leesbare ontvanger, het plafond
     * dat de aanroeper meegaf (ms) of een lege tekst, en de TTL van de index (ms).
     *
     * Antwoord:
     * - `{1, verloop, opgeruimd, plafond}` bij een start, met verloop en plafond in ms en opgeruimd 1
     *   als de start gegevens wiste of een wachtende opruiming uit de index overnam, anders 0;
     * - `{2}` als het meegegeven plafond niet in de toekomst ligt. Er is dan niets veranderd.
     *
     * Het plafond is het vroegste van start + maximale duur en het meegegeven plafond: de sessie van
     * het portaal eindigt niet later dan het portaal zegt. Alleen een start zonder andere lopende
     * sessie wist: die begint met een vers beeld. Een start naast een lopende sessie trekt de TTL van
     * de bestaande gegevens op, anders verlopen ze vóór het plafond van de nieuwe sessie.
     */
    val START = GEDEELD + "\n\n" + """
        local nu = tijdInMs()
        local plafond = nu + tonumber(ARGV[3])
        local gevraagd = tonumber(ARGV[7])
        if gevraagd then
            if gevraagd <= nu then
                return { 2 }
            end
            plafond = math.min(plafond, gevraagd)
        end

        ruimVerlopenOp(nu)

        local leeg = redis.call('ZCARD', KEYS[1]) == 0
        local opgeruimd = 0
        if leeg then
            -- Vóór de ZADD hieronder: die schuift een wachtende opruiming naar het nieuwe verloop.
            local wachtte = wachtend(ARGV[4], nu)
            if purge() > 0 or wachtte then
                opgeruimd = 1
            end
        end

        if not leeg then
            trekTtlOp(ARGV[5])
        end

        local verloop = math.min(nu + tonumber(ARGV[2]), plafond)
        redis.call('ZADD', KEYS[1], verloop, ARGV[1])
        redis.call('HSET', KEYS[2], ARGV[1], plafond)
        redis.call('PEXPIRE', KEYS[1], ARGV[5])
        redis.call('PEXPIRE', KEYS[2], ARGV[5])
        redis.call('SET', KEYS[8], ARGV[6], 'PX', ARGV[5])
        redis.call('ZADD', KEYS[3], 'GT', verloop, ARGV[4])
        houdIndexAan(ARGV[8])
        return { 1, verloop, opgeruimd, plafond }
    """.trimIndent()

    /**
     * ARGV: sessie-hash, inactiviteit (ms), kenmerk, TTL van de index (ms). Antwoord `{1, verloop, plafond}` (ms) als de
     * sessie loopt en verlengd is, anders `{0}`. Het verloop schuift nooit voorbij het plafond, dus
     * ook niet voorbij de TTL van `$k:ontvanger` die de laatste start zette.
     */
    val RAAK = GEDEELD + "\n\n" + """
        local nu = tijdInMs()
        local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
        if not score or tonumber(score) <= nu then
            return { 0 }
        end

        local plafond = tonumber(redis.call('HGET', KEYS[2], ARGV[1]))
        if not plafond then
            return { 0 }
        end

        local verloop = math.min(nu + tonumber(ARGV[2]), plafond)
        redis.call('ZADD', KEYS[1], verloop, ARGV[1])
        redis.call('ZADD', KEYS[3], 'GT', verloop, ARGV[3])
        houdIndexAan(ARGV[4])
        return { 1, verloop, plafond }
    """.trimIndent()

    /**
     * ARGV: sessie-hash. Hetzelfde antwoord als [RAAK], maar zonder te verlengen: alleen lezen. Een
     * script en geen losse `ZSCORE`, zodat "loopt nog" met dezelfde Redis-tijd beslist wordt als bij
     * [RAAK].
     */
    val LEES = GEDEELD + "\n\n" + """
        local nu = tijdInMs()
        local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
        if not score or tonumber(score) <= nu then
            return { 0 }
        end

        local plafond = tonumber(redis.call('HGET', KEYS[2], ARGV[1]))
        if not plafond then
            return { 0 }
        end

        return { 1, tonumber(score), plafond }
    """.trimIndent()

    /**
     * ARGV: sessie-hash, kenmerk, TTL van de index (ms). Antwoord `ONBEKEND`, `BEEINDIGD` of `OPGERUIMD` (zie
     * [SessieEinde]).
     *
     * Blijft er geen lopende sessie over, dan wist het script ook bij een onbekend id: er is dan
     * niemand meer voor wie de gegevens bestaan. Wist dat iets, of haalt het een wachtende
     * opruiming uit de index, dan is de uitkomst `OPGERUIMD`, zodat het wissen in het logboek komt.
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
            houdIndexAan(ARGV[3])
            if bekend then
                return 'BEEINDIGD'
            end
            return 'ONBEKEND'
        end

        local wachtte = wachtend(ARGV[2], nu)
        local gewist = purge()
        haalUitIndex(ARGV[2])
        if bekend or gewist > 0 or wachtte then
            return 'OPGERUIMD'
        end
        return 'ONBEKEND'
    """.trimIndent()

    /**
     * ARGV: kenmerk, leesbare ontvanger, vangnet-TTL (ms), TTL van de index (ms). Antwoord 1 als er
     * geen lopende sessie is en er gegevens gewist zijn, anders 0.
     *
     * Draait ná het schrijven van een ophaalronde of een aanmelding: eindigde de laatste sessie
     * terwijl die liep, dan ruimt dit het geschrevene op, in welke volgorde het ook gebeurde.
     * Dit wissen leidt zelf niet tot een logregel, dus het zet de ontvanger met score "nu" in de
     * verloopindex. Zonder NX of GT: er loopt geen sessie, dus een latere score is achterhaald.
     */
    val OPRUIMEN = GEDEELD + "\n\n" + """
        local nu = tijdInMs()
        ruimVerlopenOp(nu)

        if redis.call('ZCARD', KEYS[1]) > 0 then
            return 0
        end

        if purge() > 0 then
            redis.call('SET', KEYS[8], ARGV[2], 'PX', ARGV[3])
            redis.call('ZADD', KEYS[3], nu, ARGV[1])
            houdIndexAan(ARGV[4])
            return 1
        end
        return 0
    """.trimIndent()

    /**
     * Afwijkende KEYS: alleen de verloopindex. ARGV: het maximum aantal, en het voor- en achterstuk
     * van de sleutel `$k:ontvanger` rond het kenmerk. Antwoord per kandidaat wiens laatste sessie
     * volgens de index verlopen is: het kenmerk en de leesbare ontvanger, of een lege tekst als die
     * er niet meer is. Hier en niet pas in [VEEG]: ook een veeg die faalt of geen antwoord geeft,
     * hoort met zijn ontvanger in het logboek. Een script en geen losse `ZRANGEBYSCORE`, zodat "nu"
     * dezelfde Redis-tijd is als in [VEEG]. Een `$k:ontvanger` van het verkeerde type telt als
     * verdwenen, anders houdt één kapotte sleutel elke ronde tegen.
     */
    val KANDIDATEN = GEDEELD + "\n\n" + """
        local leden = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', tijdInMs(), 'LIMIT', 0, tonumber(ARGV[1]))
        local antwoord = {}
        for _, kenmerk in ipairs(leden) do
            local ontvanger = redis.pcall('GET', ARGV[2] .. kenmerk .. ARGV[3])
            if type(ontvanger) ~= 'string' then
                ontvanger = ''
            end
            table.insert(antwoord, kenmerk)
            table.insert(antwoord, ontvanger)
        end
        return antwoord
    """.trimIndent()

    /**
     * ARGV: kenmerk, TTL van de index (ms). Antwoord 1 als dit script de ontvanger heeft opgeruimd, anders 0.
     *
     * De index is alleen een aanwijzing: tussen [KANDIDATEN] en dit script kan een sessie gestart of
     * geraakt zijn, of een andere pod al geveegd hebben. Pas als hier blijkt dat er echt geen lopende
     * sessie meer is, wordt er gewist en verdwijnt de ontvanger uit de index; zo ruimt precies één
     * pod op en meldt het. Ook als een eerder opruimen na een schrijfactie de gegevens al wiste, is
     * dit het moment dat de verwerking eindigt, dus dan is het antwoord eveneens 1. Een ontvanger
     * wiens `$k:ontvanger` al verlopen is, wordt net zo opgeruimd.
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
            houdIndexAan(ARGV[2])
            return 0
        end

        purge()
        haalUitIndex(ARGV[1])
        return 1
    """.trimIndent()

    /**
     * Afwijkende KEYS: alleen de verloopindex. ARGV: uitstel (ms), kenmerk, TTL van de index (ms). Schuift een ontvanger
     * wiens [VEEG] faalde naar "nu + uitstel", zodat de kandidaten erachter aan de beurt komen.
     * `XX`: wie intussen uit de index verdween, komt er niet terug. `GT`: een later verloop, van een
     * sessie die intussen startte, blijft staan.
     */
    val UITSTEL = GEDEELD + "\n\n" + """
        redis.call('ZADD', KEYS[1], 'XX', 'GT', tijdInMs() + tonumber(ARGV[1]), ARGV[2])
        redis.call('PEXPIRE', KEYS[1], ARGV[3])
        return 1
    """.trimIndent()

    /**
     * Afwijkende KEYS: de verloopindex en `$k:ontvanger`. ARGV: kenmerk, leesbare ontvanger,
     * vangnet-TTL (ms), TTL van de index (ms). Zet de ontvanger met score "nu" in de index als terugval wanneer het opruimen
     * na een schrijfactie zelf faalde; de veegtaak ruimt het dan op en meldt het. `NX`: een bestaande
     * entry, van een lopende sessie of een al wachtende opruiming, blijft staan. De leesbare
     * ontvanger wordt hoe dan ook (opnieuw) gezet: de veegtaak heeft hem nodig.
     */
    val MARKEER = GEDEELD + "\n\n" + """
        redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
        redis.call('ZADD', KEYS[1], 'NX', tijdInMs(), ARGV[1])
        redis.call('PEXPIRE', KEYS[1], ARGV[4])
        return 1
    """.trimIndent()
}
