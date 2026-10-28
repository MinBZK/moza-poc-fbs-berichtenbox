package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.redis.client.Response
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenCache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.CacheCorruptedException
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
 * Stabiel token voor alert-routing: opruimen na een schrijfactie en ook [SessieRegister.markeerVoorVeeg]
 * faalden, dus de vangnet-TTL wist het geschrevene straks zonder logregel.
 */
internal const val ALERT_OPRUIMEN_NA_SCHRIJVEN = "[ALERT opruimen_na_schrijven]"

/**
 * De lopende inlogsessies per ontvanger. Een sessie-id komt hier alleen als argument binnen; opslag
 * en vergelijking gaan over `sha256(id)`, zodat een dump van Redis geen bruikbare sessies oplevert.
 */
internal interface SessieRegister {

    fun start(ontvanger: Identificatienummer): Uni<SessieStart>

    /** De geldigheid als [sessieId] een lopende sessie van [ontvanger] is, nu verlengd; anders `null`. */
    fun raak(sessieId: String, ontvanger: Identificatienummer): Uni<SessieGeldigheid?>

    /** Als [raak], maar zonder te verlengen. */
    fun lees(sessieId: String, ontvanger: Identificatienummer): Uni<SessieGeldigheid?>

    fun beeindig(sessieId: String, ontvanger: Identificatienummer): Uni<SessieEinde>

    /**
     * Wist de gegevens van [ontvanger] als er geen lopende sessie meer is, in één atomaire stap;
     * `true` als er iets gewist is. Hoort direct ná een schrijfactie die niet zelf aan een sessie
     * hangt, zodat een uitlog die ermee kruist niets laat staan. Wist het iets, dan blijft
     * [ontvanger] als wachtende opruiming in de verloopindex staan: dit wissen krijgt zelf geen
     * logregel, de veegtaak of de volgende start of uitlog meldt het.
     */
    fun ruimOpZonderSessie(ontvanger: Identificatienummer): Uni<Boolean>

    /**
     * Zet [ontvanger] als wachtende opruiming in de verloopindex, als terugval wanneer
     * [ruimOpZonderSessie] zelf faalde: dan ruimt de veegtaak het op en komt het in het logboek.
     * Staat hij er al in, met een lopende sessie of al wachtend, dan verandert er niets.
     */
    fun markeerVoorVeeg(ontvanger: Identificatienummer): Uni<Unit>

    /**
     * Hoogstens [max] ontvangers wier laatste sessie volgens de verloopindex verlopen is. Alleen een
     * kandidaat: of er echt geen sessie meer loopt, beslist [veeg].
     */
    fun verlopen(max: Int): Uni<List<VeegKandidaat>>

    /**
     * Wist de gegevens van de ontvanger met [kenmerk] en haalt hem uit de verloopindex als er geen
     * lopende sessie meer is, in één atomaire stap; `true` als déze aanroep dat deed. Van
     * gelijktijdige vegers voor dezelfde ontvanger krijgt er hooguit één `true`.
     */
    fun veeg(kenmerk: String): Uni<Boolean>

    /**
     * Schuift [kenmerk] in de verloopindex het veeg-uitstel naar achteren, na een mislukt [veeg].
     * Staat het er niet (meer) in, of ligt zijn verloop al later, dan verandert er niets.
     */
    fun stelUit(kenmerk: String): Uni<Unit>
}

/**
 * Een ontvanger uit de verloopindex. [kenmerk] is zijn lid in de index ([BerichtenCache.kenmerk]);
 * [ontvanger] de leesbare vorm, of `null` als die al verlopen of onleesbaar is of niet bij [kenmerk]
 * hoort, en dan kan het opruimen niet met betrokkene in het logboek.
 */
internal class VeegKandidaat(val kenmerk: String, val ontvanger: Identificatienummer?) {

    fun opruiming(uitkomst: OpruimUitkomst): Opruiming =
        ontvanger?.let { Opruiming.VanOntvanger(it, uitkomst) } ?: Opruiming.ZonderOntvanger(kenmerk, uitkomst)

    // Een toString belandt makkelijk in een log, en het kenmerk is een terug te rekenen hash.
    override fun toString(): String = "VeegKandidaat(ontvanger=${ontvanger?.type ?: "onbekend"}:***)"
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

    private val log = Logger.getLogger(RedisSessieRegister::class.java)

    init {
        config.valideer()
    }

    private val indexTtlMs: String = config.indexTtl.toMillis().toString()

    override fun start(ontvanger: Identificatienummer): Uni<SessieStart> {
        val sessieId = nieuweSessieId()
        val argumenten = listOf(
            hash(sessieId),
            config.inactiviteit().toMillis().toString(),
            config.maxDuur().toMillis().toString(),
            BerichtenCache.kenmerk(ontvanger),
            // Geen sessie leeft langer dan start + maximale duur, dus de laatste start bepaalt hoe
            // lang de administratie en de gegevens moeten blijven staan.
            config.dataTtl.toMillis().toString(),
            ontvanger.toCanonicalString(),
            indexTtlMs,
        )

        return voerUit(SessieScripts.START, ontvanger, argumenten).map { antwoord ->
            when (antwoord.get(0).toInteger()) {
                1 -> SessieStart(
                    sessieId = sessieId,
                    geldigheid = SessieGeldigheid(
                        geldigTot = Instant.ofEpochMilli(antwoord.get(1).toLong()),
                        uiterlijkGeldigTot = Instant.ofEpochMilli(antwoord.get(3).toLong()),
                    ),
                    restenOpgeruimd = antwoord.get(2).toLong() > 0,
                )
                else -> throw CacheCorruptedException.onverwachtScriptantwoord("start")
            }
        }
    }

    override fun raak(sessieId: String, ontvanger: Identificatienummer): Uni<SessieGeldigheid?> {
        // Wat niet de vorm van een uitgegeven id heeft, kan geen sessie zijn; dat hoeft Redis niet te zien.
        if (!Sessiecache.SESSIE_ID_VORM.matches(sessieId)) return Uni.createFrom().nullItem()

        val argumenten = listOf(
            hash(sessieId),
            config.inactiviteit().toMillis().toString(),
            BerichtenCache.kenmerk(ontvanger),
            indexTtlMs,
        )

        return voerUit(SessieScripts.RAAK, ontvanger, argumenten).map(::leesGeldigheid)
    }

    override fun lees(sessieId: String, ontvanger: Identificatienummer): Uni<SessieGeldigheid?> {
        if (!Sessiecache.SESSIE_ID_VORM.matches(sessieId)) return Uni.createFrom().nullItem()

        return voerUit(SessieScripts.LEES, ontvanger, listOf(hash(sessieId))).map(::leesGeldigheid)
    }

    override fun beeindig(sessieId: String, ontvanger: Identificatienummer): Uni<SessieEinde> {
        if (!Sessiecache.SESSIE_ID_VORM.matches(sessieId)) return Uni.createFrom().item(SessieEinde.ONBEKEND)

        val argumenten = listOf(hash(sessieId), BerichtenCache.kenmerk(ontvanger), indexTtlMs)

        // Een antwoord dat de enum niet kent, is een script dat niet bij deze code hoort (een half
        // uitgerolde versie): een eigen, leesbare fout, en geen IllegalArgumentException uit valueOf.
        return voerUit(SessieScripts.EINDE, ontvanger, argumenten).map { antwoord ->
            SessieEinde.entries.firstOrNull { it.name == antwoord.toString() }
                ?: throw CacheCorruptedException.onverwachtScriptantwoord("einde")
        }
    }

    override fun ruimOpZonderSessie(ontvanger: Identificatienummer): Uni<Boolean> {
        val argumenten = listOf(BerichtenCache.kenmerk(ontvanger), ontvanger.toCanonicalString(), config.dataTtl.toMillis().toString(), indexTtlMs)

        return voerUit(SessieScripts.OPRUIMEN, ontvanger, argumenten).map { it.toInteger() == 1 }
    }

    override fun markeerVoorVeeg(ontvanger: Identificatienummer): Uni<Unit> {
        val kenmerk = BerichtenCache.kenmerk(ontvanger)
        val argumenten = listOf(kenmerk, ontvanger.toCanonicalString(), config.dataTtl.toMillis().toString(), indexTtlMs)

        return voerUit(SessieScripts.MARKEER, listOf(VERLOOP_INDEX, ontvangerKey(kenmerk)), argumenten).replaceWith(Unit)
    }

    override fun verlopen(max: Int): Uni<List<VeegKandidaat>> {
        val argumenten = listOf(max.toString(), BerichtenCache.SLEUTEL_PREFIX, ONTVANGER_SUFFIX)

        return voerUit(SessieScripts.KANDIDATEN, listOf(VERLOOP_INDEX), argumenten).map { antwoord ->
            antwoord.map { it.toString() }.chunked(2) { (kenmerk, ontvanger) -> VeegKandidaat(kenmerk, ontvangerBij(kenmerk, ontvanger)) }
        }
    }

    /**
     * De leesbare ontvanger, alleen als hij bij [kenmerk] hoort. Een andere (door handwerk, een
     * herstelscript of een fout in de sleutelopbouw) zou als verkeerde betrokkene in het logboek
     * komen; dan liever zonder betrokkene en met het alert dat daarbij hoort.
     */
    private fun ontvangerBij(kenmerk: String, waarde: String): Identificatienummer? {
        val ontvanger = leesOntvanger(waarde) ?: return null

        if (BerichtenCache.kenmerk(ontvanger) == kenmerk) return ontvanger

        log.error("Leesbare ontvanger hoort niet bij zijn index-entry; het opruimen gaat door zonder betrokkene in het logboek")

        return null
    }

    override fun veeg(kenmerk: String): Uni<Boolean> =
        voerUit(SessieScripts.VEEG, sleutels(kenmerk), listOf(kenmerk, indexTtlMs)).map { it.toInteger() == 1 }

    override fun stelUit(kenmerk: String): Uni<Unit> {
        val argumenten = listOf(config.veegUitstel.toMillis().toString(), kenmerk, indexTtlMs)

        return voerUit(SessieScripts.UITSTEL, listOf(VERLOOP_INDEX), argumenten).replaceWith(Unit)
    }

    private fun voerUit(script: String, ontvanger: Identificatienummer, argumenten: List<String>) =
        voerUit(script, sleutels(BerichtenCache.kenmerk(ontvanger)), argumenten)

    private fun voerUit(script: String, keys: List<String>, argumenten: List<String>) =
        redis.execute("EVAL", script, keys.size.toString(), *keys.toTypedArray(), *argumenten.toTypedArray())

    companion object {
        /**
         * Stelselbreed: per ontvanger, onder zijn [BerichtenCache.kenmerk], het verloop van zijn laatst
         * verlopende sessie, voor het actief opruimen.
         */
        const val VERLOOP_INDEX = "berichtensessiecache:v4:verloop"

        /** Achter `$k`: de leesbare ontvanger bij een index-entry. */
        private const val ONTVANGER_SUFFIX = ":ontvanger"

        /** 128 bits; zie [Sessiecache.SESSIE_ID_VORM]. */
        private const val SESSIE_ID_BYTES = 16

        private val RANDOM = SecureRandom()
        private val HEX = HexFormat.of()

        /** De KEYS van elk script in [SessieScripts], in de volgorde die daar beschreven staat. */
        fun sleutels(kenmerk: String): List<String> {
            val k = BerichtenCache.SLEUTEL_PREFIX + kenmerk

            return listOf(
                "$k:sessies",
                "$k:sessies:plafond",
                VERLOOP_INDEX,
                RedisBerichtenCache.listKey(k),
                RedisBerichtenCache.statusKey(k),
                RedisBerichtenCache.lockKey(k),
                RedisBerichtenCache.sleutelsKey(k),
                ontvangerKey(kenmerk),
            )
        }

        fun ontvangerKey(kenmerk: String): String = BerichtenCache.SLEUTEL_PREFIX + kenmerk + ONTVANGER_SUFFIX

        private fun nieuweSessieId(): String {
            val bytes = ByteArray(SESSIE_ID_BYTES).also(RANDOM::nextBytes)

            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        // Leeg: verlopen. Onleesbaar telt net zo: de veegtaak ruimt op en meldt het ontbreken.
        private fun leesOntvanger(waarde: String): Identificatienummer? =
            try {
                waarde.takeIf { it.isNotEmpty() }?.let(Identificatienummer::fromHeader)
            } catch (ignored: DomainValidationException) {
                null
            }

        /** Het antwoord van [SessieScripts.RAAK] en [SessieScripts.LEES]. */
        private fun leesGeldigheid(antwoord: Response): SessieGeldigheid? =
            if (antwoord.get(0).toInteger() == 1) {
                SessieGeldigheid(Instant.ofEpochMilli(antwoord.get(1).toLong()), Instant.ofEpochMilli(antwoord.get(2).toLong()))
            } else {
                null
            }

        private fun hash(sessieId: String): String =
            HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(sessieId.toByteArray(Charsets.UTF_8)))
    }
}
