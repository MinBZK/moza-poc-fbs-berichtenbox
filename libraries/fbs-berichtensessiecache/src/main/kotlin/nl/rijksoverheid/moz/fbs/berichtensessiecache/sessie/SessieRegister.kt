package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenCache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.CacheCorruptedException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.RedisBerichtenCache
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * Stabiel token voor alert-routing: opruimen na een schrijfactie faalde of is niet bevestigd, dus
 * de vangnet-TTL wist het geschrevene straks zonder logregel.
 */
internal const val ALERT_OPRUIMEN_NA_SCHRIJVEN = "[ALERT opruimen_na_schrijven]"

/**
 * De lopende inlogsessies per ontvanger. Een sessie-id komt hier alleen als argument binnen; opslag
 * en vergelijking gaan over `sha256(id)`, zodat een dump van Redis geen bruikbare sessies oplevert.
 */
internal interface SessieRegister {

    fun start(ontvanger: Identificatienummer): Uni<SessieStart>

    /** `true` als [sessieId] een lopende sessie van [ontvanger] is. */
    fun lees(sessieId: String, ontvanger: Identificatienummer): Uni<Boolean>

    fun beeindig(sessieId: String, ontvanger: Identificatienummer): Uni<SessieEinde>

    /**
     * Wist de gegevens van [ontvanger] als er geen lopende sessie meer is, in één atomaire stap;
     * `true` als er iets gewist is. Hoort direct ná een schrijfactie die niet zelf aan een sessie
     * hangt, zodat een uitlog die ermee kruist niets laat staan. Dit wissen krijgt geen eigen
     * logregel.
     */
    fun ruimOpZonderSessie(ontvanger: Identificatienummer): Uni<Boolean>
}

/**
 * `@Startup` zodat een ongeldige sessieconfiguratie de pod bij het opstarten tegenhoudt en niet pas
 * bij de eerste inlog.
 */
@Startup
@ApplicationScoped
internal class RedisSessieRegister(
    private val redis: ReactiveRedisDataSource,
    private val config: SessieConfig,
) : SessieRegister {

    init {
        config.valideer()
    }

    override fun start(ontvanger: Identificatienummer): Uni<SessieStart> {
        val sessieId = nieuweSessieId()
        // De laatste start bepaalt hoe lang de administratie en de gegevens moeten blijven staan.
        val argumenten = listOf(hash(sessieId), config.dataTtl.toMillis().toString())

        return voerUit(SessieScripts.START, ontvanger, argumenten).map { antwoord ->
            when (antwoord.get(0).toInteger()) {
                1 -> SessieStart(
                    sessieId = sessieId,
                    restenOpgeruimd = antwoord.get(1).toLong() > 0,
                )
                else -> throw CacheCorruptedException.onverwachtScriptantwoord("start")
            }
        }
    }

    override fun lees(sessieId: String, ontvanger: Identificatienummer): Uni<Boolean> {
        // Wat niet de vorm van een uitgegeven id heeft, kan geen sessie zijn; dat hoeft Redis niet te zien.
        if (!Sessiecache.SESSIE_ID_VORM.matches(sessieId)) return Uni.createFrom().item(false)

        return voerUit(SessieScripts.LEES, ontvanger, listOf(hash(sessieId))).map { it.toInteger() == 1 }
    }

    override fun beeindig(sessieId: String, ontvanger: Identificatienummer): Uni<SessieEinde> {
        if (!Sessiecache.SESSIE_ID_VORM.matches(sessieId)) return Uni.createFrom().item(SessieEinde.ONBEKEND)

        // Een antwoord dat de enum niet kent, is een script dat niet bij deze code hoort (een half
        // uitgerolde versie): een eigen, leesbare fout, en geen IllegalArgumentException uit valueOf.
        return voerUit(SessieScripts.EINDE, ontvanger, listOf(hash(sessieId))).map { antwoord ->
            SessieEinde.entries.firstOrNull { it.name == antwoord.toString() }
                ?: throw CacheCorruptedException.onverwachtScriptantwoord("einde")
        }
    }

    override fun ruimOpZonderSessie(ontvanger: Identificatienummer): Uni<Boolean> =
        voerUit(SessieScripts.OPRUIMEN, ontvanger, emptyList()).map { it.toInteger() == 1 }

    private fun voerUit(script: String, ontvanger: Identificatienummer, argumenten: List<String>) =
        voerUit(script, sleutels(BerichtenCache.kenmerk(ontvanger)), argumenten)

    private fun voerUit(script: String, keys: List<String>, argumenten: List<String>) =
        redis.execute("EVAL", script, keys.size.toString(), *keys.toTypedArray(), *argumenten.toTypedArray())

    companion object {
        /** 128 bits; zie [Sessiecache.SESSIE_ID_VORM]. */
        private const val SESSIE_ID_BYTES = 16

        private val RANDOM = SecureRandom()
        private val HEX = HexFormat.of()

        /** De KEYS van elk script in [SessieScripts], in de volgorde die daar beschreven staat. */
        fun sleutels(kenmerk: String): List<String> {
            val k = BerichtenCache.SLEUTEL_PREFIX + kenmerk

            return listOf(
                "$k:sessies",
                RedisBerichtenCache.listKey(k),
                RedisBerichtenCache.statusKey(k),
                RedisBerichtenCache.lockKey(k),
                RedisBerichtenCache.sleutelsKey(k),
            )
        }

        private fun nieuweSessieId(): String {
            val bytes = ByteArray(SESSIE_ID_BYTES).also(RANDOM::nextBytes)

            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun hash(sessieId: String): String =
            HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(sessieId.toByteArray(Charsets.UTF_8)))
    }
}
