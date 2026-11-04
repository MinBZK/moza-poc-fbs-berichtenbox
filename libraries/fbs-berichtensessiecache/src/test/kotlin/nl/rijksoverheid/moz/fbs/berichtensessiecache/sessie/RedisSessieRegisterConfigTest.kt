package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.redis.client.Response
import io.vertx.redis.client.impl.types.SimpleStringType
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.CacheCorruptedException
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertEquals
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/**
 * Een duur of grens van nul of minder houdt de pod bij het opstarten tegen, met de
 * configuratiesleutel in de melding.
 */
class RedisSessieRegisterConfigTest {

    private val redis = mockk<ReactiveRedisDataSource>()

    @Test
    fun `de standaardwaarden worden geaccepteerd`() {
        assertDoesNotThrow { RedisSessieRegister(redis, TestSessieConfig()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["PT0S", "-PT1S"])
    fun `een duur van nul of negatief wordt per sleutel geweigerd`(waarde: String) {
        val duur = Duration.parse(waarde)
        val gevallen = mapOf(
            "inactiviteit" to TestSessieConfig(inactiviteit = duur),
            "max-duur" to TestSessieConfig(maxDuur = duur),
            "vangnet-marge" to TestSessieConfig(vangnetMarge = duur),
            "veeg-interval" to TestSessieConfig(veegInterval = duur),
        )

        gevallen.forEach { (sleutel, config) ->
            val fout = assertThrows<IllegalArgumentException> { RedisSessieRegister(redis, config) }

            assertTrue(fout.message!!.contains("berichtensessiecache.sessie.$sleutel"), "Was: ${fout.message}")
        }
    }

    @ParameterizedTest(name = "antwoord {0}")
    @ValueSource(strings = ["", "GESTOPT", "opgeruimd"])
    fun `een eindescript met een onbekend antwoord is een leesbare fout en geen IllegalArgumentException`(antwoord: String) {
        every { redis.execute(any<String>(), *anyVararg<String>()) } returns
            Uni.createFrom().item(Response.newInstance(SimpleStringType.create(antwoord)))

        val fout = assertThrows<CacheCorruptedException> {
            RedisSessieRegister(redis, TestSessieConfig()).beeindig(SESSIE_ID, Bsn("999993653")).await().indefinitely()
        }

        assertEquals("Onverwacht antwoord van het einde-script", fout.message)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `een grens per ontvanger van nul of negatief wordt geweigerd`(grens: Int) {
        val fout = assertThrows<IllegalArgumentException> { RedisSessieRegister(redis, TestSessieConfig(maxPerOntvanger = grens)) }

        assertTrue(fout.message!!.contains("berichtensessiecache.sessie.max-per-ontvanger"), "Was: ${fout.message}")
    }

    private companion object {
        const val SESSIE_ID = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
