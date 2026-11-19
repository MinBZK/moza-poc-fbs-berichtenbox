package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import nl.rijksoverheid.moz.fbs.rechtstreeks.TestApp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Optional

class MagazijnVerifiersTest {

    @Test
    fun `de App Managers uit de configuratie worden bij de start getoetst`() {
        val jwk = TestApp.jwk(TestApp.sleutelpaar().public as ECPublicKey)
        val goed = sleutel(jwk["x"] as String, jwk["y"] as String)

        assertEquals(emptyMap<Pair<String, String>, Any>(), MagazijnVerifiers.appManagerSleutels(config(emptyMap())))
        assertEquals(setOf("00000000000000001100" to "k1"), MagazijnVerifiers.appManagerSleutels(config(mapOf("00000000000000001100" to mapOf("k1" to goed)))).keys)

        val fout = listOf(
            mapOf("geen-oin" to mapOf("k1" to goed)),
            mapOf("00000000000000001100" to emptyMap()),
            mapOf("00000000000000001100" to mapOf("k1" to sleutel(jwk["x"] as String, jwk["x"] as String))),
        )

        fout.forEach { assertThrows(IllegalArgumentException::class.java) { MagazijnVerifiers.appManagerSleutels(config(it)) } }
    }

    @Test
    fun `meerdere App Managers en sleutels staan elk onder hun eigen OIN en kid`() {
        val jwk = TestApp.jwk(TestApp.sleutelpaar().public as ECPublicKey)
        val goed = sleutel(jwk["x"] as String, jwk["y"] as String)
        val config = config(mapOf("00000000000000001100" to mapOf("k1" to goed, "k2" to goed), "00000000000000001200" to mapOf("k1" to goed)))

        assertEquals(
            setOf("00000000000000001100" to "k1", "00000000000000001100" to "k2", "00000000000000001200" to "k1"),
            MagazijnVerifiers.appManagerSleutels(config).keys,
        )
    }

    @Test
    fun `een ongeldig proxy-netwerk noemt de instelling, een geldig wordt gelezen`() {
        assertEquals(2, MagazijnVerifiers.vertrouwdeProxies(config(emptyMap(), listOf("10.128.0.0/14", "2001:db8::/32"))).size)
        assertEquals(0, MagazijnVerifiers.vertrouwdeProxies(config(emptyMap())).size)

        val fout = assertThrows(IllegalArgumentException::class.java) { MagazijnVerifiers.vertrouwdeProxies(config(emptyMap(), listOf("10.0.0.0/33"))) }

        assertTrue(fout.message!!.startsWith("simulator.rechtstreeks.vertrouwde-proxies"), fout.message)
    }

    @ParameterizedTest
    @CsvSource("0, 1", "1, 1", "1000, 1", "1001, 2", "59500, 60", "60000, 60")
    fun `Retry-After rondt naar boven af op hele seconden, met minstens 1`(millis: Long, seconden: Long) {
        assertEquals(seconden, naarBoven(Duration.ofMillis(millis)))
    }

    private fun sleutel(x: String, y: String) = object : RechtstreeksConfig.Sleutel {
        override fun x() = x
        override fun y() = y
        override fun geldigTot(): Instant = Instant.parse("2099-01-01T00:00:00Z")
    }

    private fun config(appManagers: Map<String, Map<String, RechtstreeksConfig.Sleutel>>, proxies: List<String>? = null) = object : RechtstreeksConfig {
        override fun appManagers() = appManagers.mapValues { (_, sleutels) -> object : RechtstreeksConfig.AppManager { override fun sleutels() = sleutels } }
        override fun vertrouwdeProxies(): Optional<List<String>> = Optional.ofNullable(proxies)
    }
}
