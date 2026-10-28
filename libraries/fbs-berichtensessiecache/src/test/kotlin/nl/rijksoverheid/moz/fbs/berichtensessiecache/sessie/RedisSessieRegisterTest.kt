package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.vertx.mutiny.redis.client.Response
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.RealRedisTestProfile
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong

/**
 * Het sessieregister tegen een echte Redis, via de facade: starten, lezen en beëindigen. De sessies
 * overleven elke test: [RealRedisTestProfile] laat de maximale duur op de standaardwaarde.
 */
@QuarkusTest
@TestProfile(RealRedisTestProfile::class)
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
    fun `lees met een onbekende, andermans of verkeerd gevormde id gooit SessieOngeldig`() {
        val id = sessiecache.startSessie(ontvanger).sessieId

        assertThrows<SessiecacheException.SessieOngeldig> { sessiecache.leesSessie(ONBEKENDE_ID, ontvanger) }
        assertThrows<SessiecacheException.SessieOngeldig> { sessiecache.leesSessie(id, nieuweOntvanger()) }
        assertThrows<SessiecacheException.SessieOngeldig> { sessiecache.leesSessie("kort", ontvanger) }
        assertDoesNotThrow { sessiecache.leesSessie(id, ontvanger) }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3])
    fun `elke sessie beeindigen geeft BEEINDIGD en laat de rest lopen`(aantal: Int) {
        val ids = (1..aantal).map { sessiecache.startSessie(ontvanger).sessieId }

        ids.forEachIndexed { i, id ->
            assertEquals(SessieEinde.BEEINDIGD, sessiecache.beeindigSessie(id, ontvanger))
            assertThrows<SessiecacheException.SessieOngeldig> { sessiecache.leesSessie(id, ontvanger) }
            ids.drop(i + 1).forEach { over -> assertDoesNotThrow { sessiecache.leesSessie(over, ontvanger) } }
        }
    }

    @Test
    fun `einde van onbekende id geeft ONBEKEND`() {
        assertEquals(SessieEinde.ONBEKEND, sessiecache.beeindigSessie(ONBEKENDE_ID, ontvanger))
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
        private val teller = AtomicLong(System.nanoTime())

        fun nieuweOntvanger() = Oin(teller.incrementAndGet().toString().padStart(20, '0').takeLast(20))

        fun sha256(tekst: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tekst.toByteArray()))
    }
}
