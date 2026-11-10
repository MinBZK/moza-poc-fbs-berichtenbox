package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.keys.KeyScanArgs
import jakarta.enterprise.context.ApplicationScoped

/**
 * De Redis-commando's waarmee de console de sessie-administratie van de sessiecache leest en wijzigt.
 * Een eigen laag, zodat [SessieBeheer] zonder Redis te toetsen is.
 */
interface SessieOpslag {

    /** "Nu" volgens Redis, in ms: dezelfde klok waarmee de sessiecache over verlopen beslist. */
    fun nuMs(): Long

    /** De sleutels die op het glob-patroon passen. */
    fun sleutels(patroon: String): List<String>

    fun leden(administratie: Sessieadministratie): List<Sessielid>

    /** Het plafond in ms per hash; een sessie zonder (leesbaar) plafond ontbreekt. */
    fun plafonds(administratie: Sessieadministratie, hashes: List<String>): Map<String, Long>

    /**
     * Laat de sessies met deze hashes verlopen, alleen als ze nog liepen, en geeft het aantal terug.
     * Ze verdwijnen niet: de veegtaak van de uitvraag vindt een ontvanger zonder lopende sessie in de
     * verloopindex, ruimt zijn gegevens op en legt dat vast in het logboek.
     */
    fun laatVerlopen(administratie: Sessieadministratie, hashes: List<String>): Int
}

@ApplicationScoped
class RedisSessieOpslag(private val redis: RedisDataSource) : SessieOpslag {

    override fun nuMs(): Long {
        val tijd = redis.execute("TIME")

        return tijd.get(0).toLong() * MS_PER_S + tijd.get(1).toLong() / MICROS_PER_MS
    }

    // SCAN en niet KEYS: het paneel vraagt dit elke vijf seconden, en KEYS houdt Redis bezet zolang
    // hij de hele keyspace doorloopt.
    override fun sleutels(patroon: String): List<String> =
        redis.key().scan(KeyScanArgs().match(patroon).count(SCAN_STAP)).toIterable().toList()

    override fun leden(administratie: Sessieadministratie): List<Sessielid> =
        redis.sortedSet(String::class.java, String::class.java)
            .zrangeWithScores(administratie.sessies, 0, -1)
            .map { Sessielid(it.value(), it.score().toLong()) }

    override fun plafonds(administratie: Sessieadministratie, hashes: List<String>): Map<String, Long> {
        if (hashes.isEmpty()) return emptyMap()

        return redis.hash(String::class.java, String::class.java, String::class.java)
            .hmget(administratie.plafonds, *hashes.toTypedArray())
            .mapNotNull { (hash, plafond) -> plafond?.toDoubleOrNull()?.let { hash to it.toLong() } }
            .toMap()
    }

    override fun laatVerlopen(administratie: Sessieadministratie, hashes: List<String>): Int {
        if (hashes.isEmpty()) return 0

        return redis.execute(
            "EVAL",
            LAAT_VERLOPEN,
            "2",
            administratie.sessies,
            administratie.verloopindex,
            administratie.kenmerk,
            *hashes.toTypedArray(),
        ).toInteger()
    }

    companion object {

        private const val MS_PER_S = 1000L

        private const val MICROS_PER_MS = 1000L

        private const val SCAN_STAP = 1000L

        /**
         * KEYS: de sessie-ZSET en de verloopindex van dezelfde versie. ARGV: het kenmerk, en daarna de
         * hashes van de sessies die moeten verlopen. Antwoord: het aantal dat nog liep en nu verlopen is.
         *
         * Een sessie verloopt hier zoals ze vanzelf zou verlopen: haar score gaat naar "nu" volgens
         * Redis, en de bibliotheek wijst een score tot en met nu af. Daarna staat de ontvanger in de
         * verloopindex op het verloop van zijn laatste sessie, zonder `GT`: was dit de laatste lopende,
         * dan is dat "nu", en ruimt de veegtaak hem bij zijn volgende ronde op, mét een regel
         * "verlopen" in het logboek. Weghalen in plaats van laten verlopen zou die regel overslaan.
         * Eén script, zodat een sessie die tegelijk verlengd wordt niet half beëindigd achterblijft.
         */
        val LAAT_VERLOPEN = """
            local t = redis.call('TIME')
            local nu = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local verlopen = 0
            for i = 2, #ARGV do
                local score = redis.call('ZSCORE', KEYS[1], ARGV[i])
                if score and tonumber(score) > nu then
                    redis.call('ZADD', KEYS[1], 'XX', nu, ARGV[i])
                    verlopen = verlopen + 1
                end
            end
            if verlopen > 0 then
                local laatste = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
                redis.call('ZADD', KEYS[2], laatste[2], ARGV[1])
            end
            return verlopen
        """.trimIndent()
    }
}
