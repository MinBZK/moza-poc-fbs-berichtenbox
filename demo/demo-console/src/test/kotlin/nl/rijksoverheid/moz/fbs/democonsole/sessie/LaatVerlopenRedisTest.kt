package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.vertx.mutiny.redis.client.Response
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * [RedisSessieOpslag] tegen een echte Redis, met de scripts van de bibliotheek ernaast: de sessie start
 * met `START` uit `SessieScripts`, en of ze daarna verlopen is, beslissen `LEES`, `RAAK`, `KANDIDATEN`
 * en `VEEG` van diezelfde bibliotheek. De scripts komen uit de broncode (zie [SessiecacheBron]); zo
 * toetst dit wat de uitvraag werkelijk doet, zonder dat de console aan de bibliotheek hangt.
 *
 * Faalscenario zonder deze test: een fout in `LAAT_VERLOPEN` (een verwisselde KEYS-index, de verkeerde
 * kant van de ZSET, een tikfout in Lua) laat elke andere test groen. Op een gedeelde omgeving geeft
 * Beëindigen dan een 500, of zet het de verloopindex verkeerd: de veegtaak ruimt de ontvanger dan te
 * laat of nooit op, en alleen de vangnet-TTL wist hem nog, zonder regel in het logboek.
 */
@QuarkusTest
@TestProfile(LaatVerlopenRedisTest.EchteRedis::class)
class LaatVerlopenRedisTest {

    @Inject
    lateinit var redis: RedisDataSource

    @Inject
    lateinit var opslag: RedisSessieOpslag

    private val administratie = SessiecacheSleutels.administratie("$K:sessies")!!

    @BeforeEach
    fun leeg() {
        redis.flushall()
    }

    @Test
    fun `de console leest de sessie zoals de bibliotheek haar startte`() {
        val start = start(SESSIE_A)

        assertEquals(listOf(administratie.sessies), opslag.sleutels(SessiecacheSleutels.ALLE_SESSIES))
        assertEquals(listOf(Sessielid(SESSIE_A, start.verloop)), opslag.leden(administratie))
        assertEquals(mapOf(SESSIE_A to start.plafond), opslag.plafonds(administratie, listOf(SESSIE_A)))
    }

    @Test
    fun `de laatste lopende sessie verloopt op nu, en de veegtaak ruimt de ontvanger daarna op`() {
        start(SESSIE_A)
        redis.value(String::class.java).set("$K:list", "gegevens")

        val voor = opslag.nuMs()
        val verlopen = opslag.laatVerlopen(administratie, listOf(SESSIE_A))
        val na = opslag.nuMs()
        val score = score(administratie.sessies, SESSIE_A)!!

        assertEquals(1, verlopen)
        assertTrue(score in voor.toDouble()..na.toDouble(), "de sessie staat niet op nu: $score buiten [$voor, $na]")
        assertEquals(score, score(administratie.verloopindex, KENMERK), "de index staat niet op het verloop van de laatste sessie")
        assertEquals(0, leesOfRaak("LEES", SESSIE_A), "de bibliotheek leest de sessie nog als lopend")
        assertEquals(0, leesOfRaak("RAAK", SESSIE_A), "de bibliotheek verlengt de sessie nog")
        assertTrue(KENMERK in kandidaten(), "de veegtaak ziet de ontvanger niet als kandidaat")
        assertEquals(1, veeg(), "de veegtaak ruimt de ontvanger niet op, en schrijft dus ook geen regel in het logboek")
        assertFalse(redis.key().exists("$K:list"), "de gegevens staan er na de veegronde nog")
        assertNull(score(administratie.verloopindex, KENMERK), "de ontvanger staat na de veegronde nog in de index")
    }

    @Test
    fun `een sessie die al verlopen is, verloopt niet nog eens en verschuift de index niet`() {
        start(SESSIE_A)
        opslag.laatVerlopen(administratie, listOf(SESSIE_A))

        val index = score(administratie.verloopindex, KENMERK)

        assertEquals(0, opslag.laatVerlopen(administratie, listOf(SESSIE_A)))
        assertEquals(0, opslag.laatVerlopen(administratie, listOf("0".repeat(HEX_LENGTE))), "een onbekende hash telt als verlopen")
        assertEquals(index, score(administratie.verloopindex, KENMERK))
    }

    @Test
    fun `naast een andere lopende sessie volgt de index die sessie, en de veegtaak laat de ontvanger staan`() {
        start(SESSIE_A)

        val buur = start(SESSIE_B)

        assertEquals(1, opslag.laatVerlopen(administratie, listOf(SESSIE_A)))
        assertEquals(0, leesOfRaak("LEES", SESSIE_A))
        assertEquals(1, leesOfRaak("LEES", SESSIE_B), "de andere sessie is mee verlopen")
        assertEquals(buur.verloop.toDouble(), score(administratie.verloopindex, KENMERK), "de index volgt de lopende sessie niet")
        assertFalse(KENMERK in kandidaten(), "de veegtaak zou een ontvanger met een lopende sessie opruimen")
    }

    @Test
    fun `alle sessies in één keer laten verlopen zet de index op nu`() {
        start(SESSIE_A)
        start(SESSIE_B)

        assertEquals(2, opslag.laatVerlopen(administratie, listOf(SESSIE_A, SESSIE_B)))
        assertEquals(score(administratie.sessies, SESSIE_A), score(administratie.verloopindex, KENMERK))
        assertTrue(KENMERK in kandidaten())
    }

    @Test
    fun `de verloopindex houdt de TTL die de bibliotheek hem gaf`() {
        // De console kent de index-TTL van de uitvraag niet; hij laat hem dus staan. Die valt nooit
        // vóór het einde van een sessie die nog liep, dus ook niet vóór de veegronde die erop volgt.
        start(SESSIE_A)

        val voor = redis.key().pttl(administratie.verloopindex)

        opslag.laatVerlopen(administratie, listOf(SESSIE_A))

        val na = redis.key().pttl(administratie.verloopindex)

        assertTrue(na in 1..voor, "de index-TTL is veranderd: $voor ms ervoor, $na ms erna")
    }

    private fun start(hash: String): Start {
        val argumenten = listOf(
            hash,
            INACTIVITEIT_MS,
            MAX_DUUR_MS,
            MAX_PER_ONTVANGER,
            KENMERK,
            VANGNET_MS,
            ONTVANGER,
            "",
            INDEX_TTL_MS,
        )
        val antwoord = voerUit("START", SLEUTELS, argumenten)

        assertEquals(1, antwoord.get(0).toInteger(), "de bibliotheek startte de sessie niet")

        return Start(antwoord.get(1).toLong(), antwoord.get(3).toLong())
    }

    private fun leesOfRaak(script: String, hash: String): Int {
        val argumenten = if (script == "RAAK") listOf(hash, INACTIVITEIT_MS, KENMERK, INDEX_TTL_MS) else listOf(hash)

        return voerUit(script, SLEUTELS, argumenten).get(0).toInteger()
    }

    private fun kandidaten(): List<String> =
        voerUit("KANDIDATEN", listOf(administratie.verloopindex), listOf("100", VERSIEPREFIX, ":ontvanger"))
            .map { it.toString() }
            .chunked(2) { it.first() }

    private fun veeg(): Int = voerUit("VEEG", SLEUTELS, listOf(KENMERK, INDEX_TTL_MS)).toInteger()

    private fun voerUit(script: String, keys: List<String>, argumenten: List<String>): Response =
        redis.execute("EVAL", SessiecacheBron.script(script), keys.size.toString(), *keys.toTypedArray(), *argumenten.toTypedArray())

    private fun score(sleutel: String, lid: String): Double? =
        redis.sortedSet(String::class.java).zscore(sleutel, lid).let { if (it.isPresent) it.asDouble else null }

    private data class Start(val verloop: Long, val plafond: Long)

    /** Een eigen profiel, zodat de andere `@QuarkusTest`s van deze module zonder Docker blijven draaien. */
    class EchteRedis : QuarkusTestProfile {

        override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
            listOf(QuarkusTestProfile.TestResourceEntry(RedisContainer::class.java))
    }

    /** Dezelfde Redis Stack als de tests van de bibliotheek, in plaats van de compose-Redis. */
    class RedisContainer : QuarkusTestResourceLifecycleManager {

        private val container = GenericContainer<Nothing>(DockerImageName.parse("redis/redis-stack-server:7.4.0-v3"))
            .apply { withExposedPorts(REDIS_POORT) }

        override fun start(): Map<String, String> {
            container.start()

            return mapOf(
                "quarkus.redis.hosts" to "redis://${container.host}:${container.getMappedPort(REDIS_POORT)}",
                "quarkus.redis.password" to "",
            )
        }

        override fun stop() {
            container.stop()
        }

        private companion object {

            const val REDIS_POORT = 6379
        }
    }

    private companion object {

        const val HEX_LENGTE = 64

        const val VERSIEPREFIX = "berichtensessiecache:v4:"

        val KENMERK = "ab".repeat(HEX_LENGTE / 2)

        val K = VERSIEPREFIX + KENMERK

        /** In de volgorde van `RedisSessieRegister.sleutels`. */
        val SLEUTELS = listOf(
            "$K:sessies",
            "$K:sessies:plafond",
            "${VERSIEPREFIX}verloop",
            "$K:list",
            "$K:status",
            "$K:lock",
            "$K:sleutels",
            "$K:ontvanger",
        )

        val SESSIE_A = "a".repeat(HEX_LENGTE)

        val SESSIE_B = "b".repeat(HEX_LENGTE)

        const val ONTVANGER = "KVK:90000014"

        const val INACTIVITEIT_MS = "900000"

        const val MAX_DUUR_MS = "28800000"

        const val MAX_PER_ONTVANGER = "5"

        const val VANGNET_MS = "32400000"

        const val INDEX_TTL_MS = "61200000"
    }
}
