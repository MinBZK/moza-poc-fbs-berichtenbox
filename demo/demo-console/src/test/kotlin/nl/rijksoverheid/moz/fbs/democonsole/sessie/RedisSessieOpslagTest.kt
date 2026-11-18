package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.hash.HashCommands
import io.quarkus.redis.datasource.keys.KeyCommands
import io.quarkus.redis.datasource.keys.KeyScanArgs
import io.quarkus.redis.datasource.keys.KeyScanCursor
import io.quarkus.redis.datasource.sortedset.ScoredValue
import io.quarkus.redis.datasource.sortedset.SortedSetCommands
import io.vertx.mutiny.redis.client.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RedisSessieOpslagTest {

    private val sleutels = mockk<KeyCommands<String>>()

    private val zsets = mockk<SortedSetCommands<String, String>>()

    private val hashes = mockk<HashCommands<String, String, String>>()

    private val redis = mockk<RedisDataSource> {
        every { key() } returns sleutels
        every { sortedSet(String::class.java, String::class.java) } returns zsets
        every { hash(String::class.java, String::class.java, String::class.java) } returns hashes
    }

    private val opslag = RedisSessieOpslag(redis)

    @Test
    fun `nu komt uit de klok van Redis, in milliseconden`() {
        // Dezelfde klok als de scripts van de bibliotheek: met de klok van de console zou een sessie
        // die net beëindigd is nog even als lopend te zien zijn.
        val tijd = mockk<Response> {
            every { get(0) } returns mockk { every { toLong() } returns 1_800_000_000L }
            every { get(1) } returns mockk { every { toLong() } returns 123_999L }
        }

        every { redis.execute("TIME") } returns tijd

        assertEquals(1_800_000_000_123L, opslag.nuMs())
    }

    @Test
    fun `de sleutels komen uit SCAN met het patroon en niet uit KEYS`() {
        val argumenten = slot<KeyScanArgs>()
        val cursor = mockk<KeyScanCursor<String>> { every { toIterable() } returns listOf("x", "y") }

        every { sleutels.scan(capture(argumenten)) } returns cursor

        assertEquals(listOf("x", "y"), opslag.sleutels(SessiecacheSleutels.ALLE_SESSIES))
        assertTrue(
            argumenten.captured.toArgs().windowed(2).contains(listOf("MATCH", SessiecacheSleutels.ALLE_SESSIES)),
            "SCAN zonder het patroon: ${argumenten.captured.toArgs()}",
        )
        verify(exactly = 0) { sleutels.keys(any()) }
    }

    @Test
    fun `een sleutel die SCAN twee keer teruggeeft, telt één keer`() {
        // SCAN belooft "minstens één keer": tijdens een rehash van de keyspace kan een sleutel
        // terugkomen, en dan stond één sessie twee keer in het paneel.
        val cursor = mockk<KeyScanCursor<String>> { every { toIterable() } returns listOf("x", "y", "x") }

        every { sleutels.scan(any()) } returns cursor

        assertEquals(listOf("x", "y"), opslag.sleutels(SessiecacheSleutels.ALLE_SESSIES))
    }

    @Test
    fun `de leden komen met hun verloop uit de sessie-ZSET`() {
        every { zsets.zrangeWithScores(ADMINISTRATIE.sessies, 0, -1) } returns listOf(
            ScoredValue("a", 1_800_000_000_000.0),
            ScoredValue("b", 1_800_000_060_000.0),
        )

        assertEquals(
            listOf(Sessielid("a", 1_800_000_000_000L), Sessielid("b", 1_800_000_060_000L)),
            opslag.leden(ADMINISTRATIE),
        )
    }

    @Test
    fun `een ontbrekend of onleesbaar plafond valt weg`() {
        every { hashes.hmget(ADMINISTRATIE.plafonds, "a", "b", "c", "d") } returns mapOf(
            "a" to "1800000000000",
            "b" to null,
            "c" to "geen getal",
            // Zo schrijft Lua een groot getal soms weg; het blijft hetzelfde moment.
            "d" to "1.8e+12",
        )

        assertEquals(
            mapOf("a" to 1_800_000_000_000L, "d" to 1_800_000_000_000L),
            opslag.plafonds(ADMINISTRATIE, listOf("a", "b", "c", "d")),
        )
    }

    @Test
    fun `zonder hashes wordt er geen plafond gevraagd`() {
        assertEquals(emptyMap<String, Long>(), opslag.plafonds(ADMINISTRATIE, emptyList()))

        verify(exactly = 0) { hashes.hmget(any(), *anyVararg()) }
    }

    @Test
    fun `verlopen draait het script met de sessie-ZSET en de verloopindex van dezelfde versie`() {
        val antwoord = mockk<Response> { every { toInteger() } returns 2 }

        every {
            redis.execute(
                "EVAL",
                RedisSessieOpslag.LAAT_VERLOPEN,
                "2",
                ADMINISTRATIE.sessies,
                ADMINISTRATIE.verloopindex,
                ADMINISTRATIE.kenmerk,
                "a",
                "b",
            )
        } returns antwoord

        assertEquals(2, opslag.laatVerlopen(ADMINISTRATIE, listOf("a", "b")))
    }

    @Test
    fun `zonder hashes draait het script niet`() {
        assertEquals(0, opslag.laatVerlopen(ADMINISTRATIE, emptyList()))

        verify(exactly = 0) { redis.execute(any<String>(), *anyVararg()) }
    }

    private companion object {

        val ADMINISTRATIE: Sessieadministratie = SessiecacheSleutels.administratie(
            "berichtensessiecache:v4:6a9056ae50ec26106e793b6cb9382d9ad2bcef88ae735fdc802c8206fd50dd3e:sessies",
        )!!
    }
}
