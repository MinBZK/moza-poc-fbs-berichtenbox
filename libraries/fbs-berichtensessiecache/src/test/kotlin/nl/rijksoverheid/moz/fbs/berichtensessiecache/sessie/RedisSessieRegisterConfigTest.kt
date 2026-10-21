package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.mockk.mockk
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

    private data class Config(
        val inactiviteit: Duration = Duration.ofMinutes(15),
        val maxDuur: Duration = Duration.ofHours(12),
        val vangnetMarge: Duration = Duration.ofHours(1),
        val maxPerOntvanger: Int = 5,
        val veegInterval: Duration = Duration.ofSeconds(30),
    ) : SessieConfig {
        override fun inactiviteit() = inactiviteit
        override fun maxDuur() = maxDuur
        override fun vangnetMarge() = vangnetMarge
        override fun maxPerOntvanger() = maxPerOntvanger
        override fun veegInterval() = veegInterval
    }

    @Test
    fun `de standaardwaarden worden geaccepteerd`() {
        assertDoesNotThrow { RedisSessieRegister(redis, Config()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["PT0S", "-PT1S"])
    fun `een duur van nul of negatief wordt per sleutel geweigerd`(waarde: String) {
        val duur = Duration.parse(waarde)
        val gevallen = mapOf(
            "inactiviteit" to Config(inactiviteit = duur),
            "max-duur" to Config(maxDuur = duur),
            "vangnet-marge" to Config(vangnetMarge = duur),
            "veeg-interval" to Config(veegInterval = duur),
        )

        gevallen.forEach { (sleutel, config) ->
            val fout = assertThrows<IllegalArgumentException> { RedisSessieRegister(redis, config) }

            assertTrue(fout.message!!.contains("berichtensessiecache.sessie.$sleutel"), "Was: ${fout.message}")
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `een grens per ontvanger van nul of negatief wordt geweigerd`(grens: Int) {
        val fout = assertThrows<IllegalArgumentException> { RedisSessieRegister(redis, Config(maxPerOntvanger = grens)) }

        assertTrue(fout.message!!.contains("berichtensessiecache.sessie.max-per-ontvanger"), "Was: ${fout.message}")
    }
}
