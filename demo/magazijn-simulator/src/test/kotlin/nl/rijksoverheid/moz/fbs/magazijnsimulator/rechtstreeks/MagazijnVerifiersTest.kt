package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import nl.rijksoverheid.moz.fbs.rechtstreeks.TestApp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.interfaces.ECPublicKey
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

    private fun sleutel(x: String, y: String) = object : RechtstreeksConfig.Sleutel {
        override fun x() = x
        override fun y() = y
        override fun geldigTot(): Instant = Instant.parse("2099-01-01T00:00:00Z")
    }

    private fun config(appManagers: Map<String, Map<String, RechtstreeksConfig.Sleutel>>) = object : RechtstreeksConfig {
        override fun appManagers() = appManagers.mapValues { (_, sleutels) -> object : RechtstreeksConfig.AppManager { override fun sleutels() = sleutels } }
        override fun vertrouwdeProxies(): Optional<List<String>> = Optional.empty()
    }
}
