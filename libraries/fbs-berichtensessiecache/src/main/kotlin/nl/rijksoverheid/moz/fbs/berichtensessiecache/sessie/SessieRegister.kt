package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenCache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.RedisBerichtenCache
import nl.rijksoverheid.moz.fbs.kern.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

/**
 * De lopende inlogsessies per ontvanger. Een sessie-id komt hier alleen als argument binnen; opslag
 * en vergelijking gaan over `sha256(id)`, zodat een dump van Redis geen bruikbare sessies oplevert.
 */
internal interface SessieRegister {

    /** Start een sessie, of levert `null` als de ontvanger al het maximum aantal lopende sessies heeft. */
    fun start(ontvanger: Identificatienummer): Uni<SessieStart?>

    /** Of [sessieId] een lopende sessie van [ontvanger] is; zo ja, dan is hij nu verlengd. */
    fun raak(sessieId: String, ontvanger: Identificatienummer): Uni<Boolean>

    fun beeindig(sessieId: String, ontvanger: Identificatienummer): Uni<SessieEinde>

    /**
     * Wist de gegevens onder [cacheKey] als er geen lopende sessie meer is, in één atomaire stap;
     * `true` als er iets gewist is. Hoort direct ná een schrijfactie die niet zelf aan een sessie
     * hangt, zodat een uitlog die ermee kruist niets laat staan.
     */
    fun ruimOpZonderSessie(cacheKey: String): Uni<Boolean>

    /**
     * Hoogstens [max] ontvangers wier laatste sessie volgens de verloopindex verlopen is. Alleen een
     * kandidaat: of er echt geen sessie meer loopt, beslist [veeg].
     */
    fun verlopen(max: Int): Uni<List<Identificatienummer>>

    /**
     * Wist de gegevens van [ontvanger] en haalt hem uit de verloopindex als er geen lopende sessie
     * meer is, in één atomaire stap; `true` als déze aanroep dat deed. Van gelijktijdige vegers voor
     * dezelfde ontvanger krijgt er hooguit één `true`.
     */
    fun veeg(ontvanger: Identificatienummer): Uni<Boolean>

    /**
     * Schuift [ontvanger] in de verloopindex het veeg-uitstel naar achteren, na een mislukt [veeg].
     * Staat hij er niet (meer) in, of ligt zijn verloop al later, dan verandert er niets.
     */
    fun stelUit(ontvanger: Identificatienummer): Uni<Unit>
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
        // Een duur van nul laat elke sessie direct verlopen, of — bij het veeginterval — de veegtaak
        // onafgebroken draaien; een grens van nul weigert elke inlog.
        require(config.inactiviteit().isPositive) {
            "berichtensessiecache.sessie.inactiviteit (${config.inactiviteit()}) moet groter zijn dan 0"
        }

        require(config.maxDuur().isPositive) {
            "berichtensessiecache.sessie.max-duur (${config.maxDuur()}) moet groter zijn dan 0"
        }

        require(config.vangnetMarge().isPositive) {
            "berichtensessiecache.sessie.vangnet-marge (${config.vangnetMarge()}) moet groter zijn dan 0"
        }

        require(config.maxPerOntvanger() > 0) {
            "berichtensessiecache.sessie.max-per-ontvanger (${config.maxPerOntvanger()}) moet groter zijn dan 0"
        }

        require(config.veegInterval().isPositive) {
            "berichtensessiecache.sessie.veeg-interval (${config.veegInterval()}) moet groter zijn dan 0"
        }
    }

    override fun start(ontvanger: Identificatienummer): Uni<SessieStart?> {
        val sessieId = nieuweSessieId()
        val argumenten = listOf(
            hash(sessieId),
            config.inactiviteit().toMillis().toString(),
            config.maxDuur().toMillis().toString(),
            config.maxPerOntvanger().toString(),
            ontvanger.toCanonicalString(),
            // Geen sessie leeft langer dan start + maximale duur, dus de laatste start bepaalt hoe
            // lang de administratie en de gegevens moeten blijven staan.
            config.dataTtl.toMillis().toString(),
        )

        return voerUit(SessieScripts.START, ontvanger, argumenten).map { antwoord ->
            if (antwoord.get(0).toInteger() == 0) {
                null
            } else {
                SessieStart(
                    sessieId = sessieId,
                    uiterlijkGeldigTot = Instant.ofEpochMilli(antwoord.get(1).toLong()),
                    restenOpgeruimd = antwoord.get(2).toLong() > 0,
                )
            }
        }
    }

    override fun raak(sessieId: String, ontvanger: Identificatienummer): Uni<Boolean> {
        // Wat niet de vorm van een uitgegeven id heeft, kan geen sessie zijn; dat hoeft Redis niet te zien.
        if (!SESSIE_ID_VORM.matches(sessieId)) return Uni.createFrom().item(false)

        val argumenten = listOf(
            hash(sessieId),
            config.inactiviteit().toMillis().toString(),
            ontvanger.toCanonicalString(),
        )

        return voerUit(SessieScripts.RAAK, ontvanger, argumenten).map { it.toInteger() == 1 }
    }

    override fun beeindig(sessieId: String, ontvanger: Identificatienummer): Uni<SessieEinde> {
        if (!SESSIE_ID_VORM.matches(sessieId)) return Uni.createFrom().item(SessieEinde.ONBEKEND)

        val argumenten = listOf(hash(sessieId), ontvanger.toCanonicalString())

        return voerUit(SessieScripts.EINDE, ontvanger, argumenten).map { SessieEinde.valueOf(it.toString()) }
    }

    override fun ruimOpZonderSessie(cacheKey: String): Uni<Boolean> =
        voerUit(SessieScripts.OPRUIMEN, sleutels(cacheKey), emptyList()).map { it.toInteger() == 1 }

    override fun verlopen(max: Int): Uni<List<Identificatienummer>> =
        voerUit(SessieScripts.KANDIDATEN, listOf(VERLOOP_INDEX), listOf(max.toString())).flatMap { antwoord ->
            val leden = antwoord.map { it.toString() }.associateWith(::leesOntvanger)
            val onleesbaar = leden.filterValues { it == null }.keys
            val ontvangers = leden.values.filterNotNull()

            if (onleesbaar.isEmpty()) {
                Uni.createFrom().item(ontvangers)
            } else {
                // Zonder de waarde: een lid dat net niet als ontvanger te lezen is, kan een BSN bevatten.
                log.warnf("%d onleesbare leden uit de verloopindex verwijderd", onleesbaar.size)
                redis.sortedSet(String::class.java).zrem(VERLOOP_INDEX, *onleesbaar.toTypedArray()).replaceWith(ontvangers)
            }
        }

    override fun veeg(ontvanger: Identificatienummer): Uni<Boolean> =
        voerUit(SessieScripts.VEEG, ontvanger, listOf(ontvanger.toCanonicalString())).map { it.toInteger() == 1 }

    override fun stelUit(ontvanger: Identificatienummer): Uni<Unit> {
        val argumenten = listOf(config.veegUitstel.toMillis().toString(), ontvanger.toCanonicalString())

        return voerUit(SessieScripts.UITSTEL, listOf(VERLOOP_INDEX), argumenten).replaceWith(Unit)
    }

    private fun voerUit(script: String, ontvanger: Identificatienummer, argumenten: List<String>) =
        voerUit(script, sleutels(ontvanger), argumenten)

    private fun voerUit(script: String, keys: List<String>, argumenten: List<String>) =
        redis.execute("EVAL", script, keys.size.toString(), *keys.toTypedArray(), *argumenten.toTypedArray())

    companion object {
        private val log = Logger.getLogger(RedisSessieRegister::class.java)

        /** Stelselbreed: per ontvanger het verloop van zijn laatst verlopende sessie, voor het actief opruimen. */
        const val VERLOOP_INDEX = "berichtensessiecache:v4:verloop"

        /** 128 bits, base64url zonder opvulling. */
        private const val SESSIE_ID_BYTES = 16
        private val SESSIE_ID_VORM = Regex("^[A-Za-z0-9_-]{22}$")

        private val RANDOM = SecureRandom()
        private val HEX = HexFormat.of()

        /** De KEYS van elk script in [SessieScripts], in de volgorde die daar beschreven staat. */
        fun sleutels(ontvanger: Identificatienummer): List<String> = sleutels(BerichtenCache.cacheKey(ontvanger))

        fun sleutels(k: String): List<String> = listOf(
            "$k:sessies",
            "$k:sessies:plafond",
            VERLOOP_INDEX,
            RedisBerichtenCache.listKey(k),
            RedisBerichtenCache.statusKey(k),
            RedisBerichtenCache.lockKey(k),
            RedisBerichtenCache.sleutelsKey(k),
        )

        private fun nieuweSessieId(): String {
            val bytes = ByteArray(SESSIE_ID_BYTES).also(RANDOM::nextBytes)

            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun leesOntvanger(lid: String): Identificatienummer? =
            try {
                Identificatienummer.fromHeader(lid)
            } catch (ignored: DomainValidationException) {
                null
            }

        private fun hash(sessieId: String): String =
            HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(sessieId.toByteArray(Charsets.UTF_8)))
    }
}
