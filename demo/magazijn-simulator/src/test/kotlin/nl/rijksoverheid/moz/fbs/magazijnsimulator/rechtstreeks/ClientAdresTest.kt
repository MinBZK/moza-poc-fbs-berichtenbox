package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import nl.rijksoverheid.moz.fbs.rechtstreeks.TestApp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Optional

class ClientAdresTest {

    private val proxy = listOf(ClientAdres.Netwerk.van("10.128.0.0/14"))

    @Test
    fun `de sleutel is het IPv4-adres, of het IPv6-prefix van 64 bits`() {
        assertEquals("192.0.2.1", ClientAdres.clientSleutel("192.0.2.1"))
        assertEquals("192.0.2.1", ClientAdres.clientSleutel("::ffff:192.0.2.1"))
        assertEquals(ClientAdres.clientSleutel("2001:db8:1:2::1"), ClientAdres.clientSleutel("2001:db8:1:2:ffff::9"))
        assertNotEquals(ClientAdres.clientSleutel("2001:db8:1:2::1"), ClientAdres.clientSleutel("2001:db8:1:3::1"))

        listOf(null, "", "unknown", "localhost", "_verborgen").forEach { assertEquals(ClientAdres.ONBEKEND, ClientAdres.clientSleutel(it)) }
    }

    @Test
    fun `achter een vertrouwde proxy telt de laatste waarde van X-Forwarded-For, niet wat de client ervoor zette`() {
        assertEquals("198.51.100.7", ClientAdres.bepaal("10.128.3.4", "203.0.113.9, 198.51.100.7", proxy))
        assertEquals("198.51.100.7", ClientAdres.bepaal("10.128.3.4", "198.51.100.7", proxy))
        assertEquals(ClientAdres.ONBEKEND, ClientAdres.bepaal("10.128.3.4", "198.51.100.7, unknown", proxy))
    }

    @Test
    fun `zonder vertrouwde proxy telt het adres van de peer, ook als er een X-Forwarded-For meekomt`() {
        assertEquals("192.0.2.1", ClientAdres.bepaal("192.0.2.1", "198.51.100.7", proxy))
        assertEquals("10.128.3.4", ClientAdres.bepaal("10.128.3.4", "198.51.100.7", emptyList()))
        assertEquals("10.128.3.4", ClientAdres.bepaal("10.128.3.4", null, proxy))
        assertEquals(ClientAdres.ONBEKEND, ClientAdres.bepaal(null, "198.51.100.7", proxy))
    }

    @Test
    fun `een netwerk dekt precies zijn prefix, per adresfamilie`() {
        val v6 = ClientAdres.Netwerk.van("2001:db8::/32")
        val host = ClientAdres.Netwerk.van("127.0.0.1")

        assertEquals("198.51.100.7", ClientAdres.bepaal("2001:db8:ffff::1", "198.51.100.7", listOf(v6)))
        assertEquals(ClientAdres.clientSleutel("2001:db9::1"), ClientAdres.bepaal("2001:db9::1", "198.51.100.7", listOf(v6)))
        assertEquals("10.131.255.255", ClientAdres.bepaal("10.131.255.255", "198.51.100.7", emptyList()))
        assertEquals("198.51.100.7", ClientAdres.bepaal("10.131.255.255", "198.51.100.7", proxy))
        assertEquals("10.132.0.0", ClientAdres.bepaal("10.132.0.0", "198.51.100.7", proxy))
        assertEquals("198.51.100.7", ClientAdres.bepaal("127.0.0.1", "198.51.100.7", listOf(host)))
        assertEquals("127.0.0.2", ClientAdres.bepaal("127.0.0.2", "198.51.100.7", listOf(host)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "geen-ip/8", "10.0.0.0/33", "10.0.0.0/-1", "10.0.0.0/x", "::/129"])
    fun `een onbruikbaar netwerk in de configuratie wordt geweigerd`(tekst: String) {
        assertThrows(IllegalArgumentException::class.java) { ClientAdres.Netwerk.van(tekst) }
    }

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
