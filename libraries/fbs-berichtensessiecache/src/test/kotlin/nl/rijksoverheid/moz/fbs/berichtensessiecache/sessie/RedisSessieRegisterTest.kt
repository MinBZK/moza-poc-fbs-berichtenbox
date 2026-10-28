package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.vertx.mutiny.redis.client.Response
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenCache
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Het sessieregister tegen een echte Redis, via de facade: starten, lezen en beëindigen. De sessies
 * overleven elke test ([OpruimRedisTestProfile]).
 */
@QuarkusTest
@TestProfile(OpruimRedisTestProfile::class)
class RedisSessieRegisterTest {

    @Inject
    lateinit var sessiecache: Sessiecache

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    // Uniek per test: de Redis-container wordt gedeeld tussen de tests van dit profiel.
    private val ontvanger = nieuweOntvanger()

    @Test
    fun `start levert unieke id's van 22 tekens`() {
        val ids = (1..5).map { sessiecache.startSessie(ontvanger).sessieId }

        assertEquals(ids.size, ids.toSet().size, "Dubbele sessie-id: $ids")
        ids.forEach { assertTrue(SESSIE_ID_VORM.matches(it), "Geen geldige sessie-id: $it") }
    }

    @Test
    fun `start meldt geen resten bij een eerste sessie`() {
        val start = sessiecache.startSessie(ontvanger)

        assertFalse(start.restenOpgeruimd)
    }

    @Test
    fun `lees met een onbekende, andermans of verkeerd gevormde id gooit SessieOngeldig`() {
        val id = sessiecache.startSessie(ontvanger).sessieId

        assertThrows<SessiecacheException.SessieOngeldig> { sessiecache.leesSessie(ONBEKENDE_ID, ontvanger) }
        assertThrows<SessiecacheException.SessieOngeldig> { sessiecache.leesSessie(id, nieuweOntvanger()) }
        assertThrows<SessiecacheException.SessieOngeldig> { sessiecache.leesSessie("kort", ontvanger) }
        assertDoesNotThrow { sessiecache.leesSessie(id, ontvanger) }
    }

    @Test
    fun `start zonder lopende sessie ruimt achtergebleven gegevens op`() {
        val k = BerichtenCache.cacheKey(ontvanger)
        redis.value(String::class.java).set("$k:status", "{}").await().atMost(WACHTTIJD)
        redis.list(String::class.java).rpush("$k:list", "{}").await().atMost(WACHTTIJD)

        val start = sessiecache.startSessie(ontvanger)

        assertTrue(start.restenOpgeruimd)
        assertEquals(0, redis.key().exists("$k:status", "$k:list").await().atMost(WACHTTIJD))
    }

    @Test
    fun `een start gelijktijdig met het einde van de laatste sessie laat een geldige sessie achter`() {
        // Welke van de twee eerst gaat, verschilt per herhaling; in beide volgordes moet de nieuwe
        // sessie bruikbaar zijn, en zijn de oude gegevens weg of nog van een lopende sessie, nooit half.
        val pool = Executors.newFixedThreadPool(2)
        val volgordes = mutableSetOf<SessieEinde>()

        try {
            repeat(HERHALINGEN) {
                val ontvanger = nieuweOntvanger()
                val k = BerichtenCache.cacheKey(ontvanger)
                val eerste = sessiecache.startSessie(ontvanger)
                redis.list(String::class.java).rpush("$k:list", "{}").await().atMost(WACHTTIJD)
                val poort = CyclicBarrier(2)

                val einde = pool.submit<SessieEinde> {
                    poort.await(10, TimeUnit.SECONDS)
                    sessiecache.beeindigSessie(eerste.sessieId, ontvanger)
                }
                val start = pool.submit<SessieStart> {
                    poort.await(10, TimeUnit.SECONDS)
                    sessiecache.startSessie(ontvanger)
                }
                val uitkomst = einde.get(10, TimeUnit.SECONDS)
                val nieuw = start.get(10, TimeUnit.SECONDS)

                volgordes += uitkomst
                assertDoesNotThrow { sessiecache.leesSessie(nieuw.sessieId, ontvanger) }
                assertFalse(nieuw.restenOpgeruimd, "Er was geen achtergebleven rest om op te ruimen")
                assertEquals(uitkomst == SessieEinde.BEEINDIGD, redis.key().exists("$k:list").await().atMost(WACHTTIJD), "Uitkomst $uitkomst")
            }
        } finally {
            pool.shutdownNow()
        }

        assertTrue(volgordes.all { it == SessieEinde.OPGERUIMD || it == SessieEinde.BEEINDIGD }, "Uitkomsten: $volgordes")
    }

    @Test
    fun `start naast een lopende sessie laat de gegevens staan`() {
        val k = BerichtenCache.cacheKey(ontvanger)
        sessiecache.startSessie(ontvanger)
        redis.value(String::class.java).set("$k:status", "{}").await().atMost(WACHTTIJD)

        val tweede = sessiecache.startSessie(ontvanger)

        assertFalse(tweede.restenOpgeruimd)
        assertTrue(redis.key().exists("$k:status").await().atMost(WACHTTIJD))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3])
    fun `alle sessies op een na beeindigen geeft BEEINDIGD en de laatste OPGERUIMD`(aantal: Int) {
        val ids = (1..aantal).map { sessiecache.startSessie(ontvanger).sessieId }

        ids.dropLast(1).forEach { assertEquals(SessieEinde.BEEINDIGD, sessiecache.beeindigSessie(it, ontvanger)) }

        assertEquals(SessieEinde.OPGERUIMD, sessiecache.beeindigSessie(ids.last(), ontvanger))
        assertThrows<SessiecacheException.SessieOngeldig> { sessiecache.leesSessie(ids.last(), ontvanger) }
    }

    @Test
    fun `einde van de laatste sessie ruimt gegevens en sessiesleutels op`() {
        val k = BerichtenCache.cacheKey(ontvanger)
        val id = sessiecache.startSessie(ontvanger).sessieId
        redis.value(String::class.java).set("$k:status", "{}").await().atMost(WACHTTIJD)
        redis.list(String::class.java).rpush("$k:list", "{}").await().atMost(WACHTTIJD)
        redis.value(String::class.java).set("$k:lock", "1").await().atMost(WACHTTIJD)

        sessiecache.beeindigSessie(id, ontvanger)

        val over = redis.key().exists("$k:status", "$k:list", "$k:lock", "$k:sessies").await().atMost(WACHTTIJD)
        assertEquals(0, over)
    }

    @Test
    fun `einde van onbekende id geeft ONBEKEND`() {
        assertEquals(SessieEinde.ONBEKEND, sessiecache.beeindigSessie(ONBEKENDE_ID, ontvanger))
    }

    @Test
    fun `einde van onbekende id zonder lopende sessie wist achtergebleven gegevens en meldt OPGERUIMD`() {
        val k = BerichtenCache.cacheKey(ontvanger)
        redis.value(String::class.java).set("$k:status", "{}").await().atMost(WACHTTIJD)

        assertEquals(SessieEinde.OPGERUIMD, sessiecache.beeindigSessie(ONBEKENDE_ID, ontvanger))
        assertFalse(redis.key().exists("$k:status").await().atMost(WACHTTIJD))
    }

    @Test
    fun `einde van onbekende id naast een lopende sessie geeft ONBEKEND en laat die sessie staan`() {
        val id = sessiecache.startSessie(ontvanger).sessieId

        assertEquals(SessieEinde.ONBEKEND, sessiecache.beeindigSessie(ONBEKENDE_ID, ontvanger))
        assertDoesNotThrow { sessiecache.leesSessie(id, ontvanger) }
    }

    @Test
    fun `einde met id van andere ontvanger geeft ONBEKEND en laat de sessie staan`() {
        val id = sessiecache.startSessie(ontvanger).sessieId

        assertEquals(SessieEinde.ONBEKEND, sessiecache.beeindigSessie(id, nieuweOntvanger()))
        assertDoesNotThrow { sessiecache.leesSessie(id, ontvanger) }
    }

    @Test
    fun `einde met een id van de verkeerde vorm geeft ONBEKEND`() {
        sessiecache.startSessie(ontvanger)

        assertEquals(SessieEinde.ONBEKEND, sessiecache.beeindigSessie("geen-sessie", ontvanger))
    }

    @Test
    fun `Redis bevat de sessie-id nergens in klare tekst`() {
        val ids = (1..3).map { sessiecache.startSessie(ontvanger).sessieId }

        val inhoud = alleSleutelsEnWaarden()

        // Wel de hash: anders slaagt deze test ook als het register niets schrijft.
        ids.forEach { id -> assertTrue(inhoud.any { it.contains(sha256(id)) }, "Hash van de sessie niet in Redis") }
        ids.forEach { id -> assertTrue(inhoud.none { it.contains(id) }, "Sessie-id in klare tekst in Redis") }
    }

    private fun alleSleutelsEnWaarden(): List<String> {
        val gevonden = mutableListOf<String>()
        var cursor = "0"

        do {
            val antwoord = opdracht("SCAN", cursor, "COUNT", "1000")
            cursor = antwoord.get(0).toString()

            antwoord.get(1).forEach { sleutelAntwoord ->
                val sleutel = sleutelAntwoord.toString()
                gevonden += sleutel
                gevonden += waardenVan(sleutel)
            }
        } while (cursor != "0")

        return gevonden
    }

    // Alleen opdrachten met een platte lijst als antwoord, zodat RESP2 en RESP3 hetzelfde opleveren.
    // Scores blijven buiten beschouwing: dat zijn getallen.
    private fun waardenVan(sleutel: String): List<String> = when (opdracht("TYPE", sleutel).toString()) {
        "string" -> listOf(opdracht("GET", sleutel).toString())
        "hash" -> lijst("HKEYS", sleutel) + lijst("HVALS", sleutel)
        "zset" -> lijst("ZRANGE", sleutel, "0", "-1")
        "set" -> lijst("SMEMBERS", sleutel)
        "list" -> lijst("LRANGE", sleutel, "0", "-1")
        else -> emptyList()
    }

    private fun lijst(vararg delen: String): List<String> = opdracht(*delen).map { it.toString() }

    private fun opdracht(vararg delen: String): Response =
        redis.execute(delen.first(), *delen.drop(1).toTypedArray()).await().atMost(WACHTTIJD)

    private companion object {
        val WACHTTIJD: Duration = Duration.ofSeconds(5)
        val SESSIE_ID_VORM = Regex("^[A-Za-z0-9_-]{22}$")
        const val ONBEKENDE_ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val HERHALINGEN = 20
        private val teller = AtomicLong(System.nanoTime())

        fun nieuweOntvanger() = Oin(teller.incrementAndGet().toString().padStart(20, '0').takeLast(20))

        fun sha256(tekst: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tekst.toByteArray()))
    }
}
