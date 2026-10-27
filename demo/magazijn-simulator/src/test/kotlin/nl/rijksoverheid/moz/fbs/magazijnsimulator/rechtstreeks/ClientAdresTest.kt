package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ClientAdresTest {

    private val proxy = listOf(ClientAdres.Netwerk.van("10.128.0.0/14"))

    private fun sleutel(adres: String?) = ClientAdres.bepaal(adres, emptyList(), emptyList())

    private fun bepaal(peer: String?, xff: String?, vertrouwd: List<ClientAdres.Netwerk>) =
        ClientAdres.bepaal(peer, listOfNotNull(xff), vertrouwd)

    @Test
    fun `de sleutel is het IPv4-adres, of het IPv6-prefix van 64 bits`() {
        assertEquals("192.0.2.1", sleutel("192.0.2.1"))
        assertEquals("192.0.2.1", sleutel("::ffff:192.0.2.1"))
        assertEquals(sleutel("2001:db8:1:2::1"), sleutel("2001:db8:1:2:ffff::9"))
        assertNotEquals(sleutel("2001:db8:1:2::1"), sleutel("2001:db8:1:3::1"))

        listOf(null, "", "unknown", "localhost", "_verborgen").forEach { assertEquals(ClientAdres.ONBEKEND, sleutel(it)) }
    }

    @Test
    fun `achter een vertrouwde proxy telt de laatste waarde van X-Forwarded-For, niet wat de client ervoor zette`() {
        assertEquals("198.51.100.7", bepaal("10.128.3.4", "203.0.113.9, 198.51.100.7", proxy))
        assertEquals("198.51.100.7", bepaal("10.128.3.4", "198.51.100.7", proxy))
        assertEquals(ClientAdres.ONBEKEND, bepaal("10.128.3.4", "198.51.100.7, unknown", proxy))
    }

    @Test
    fun `over meerdere regels van de header telt de laatste, en een poort valt weg`() {
        assertEquals("198.51.100.7", ClientAdres.bepaal("10.128.3.4", listOf("6.6.6.6", "198.51.100.7"), proxy))
        assertEquals("198.51.100.7", ClientAdres.bepaal("10.128.3.4", listOf("6.6.6.6, 7.7.7.7", "198.51.100.7:5678"), proxy))
        assertEquals(sleutel("2001:db8::1"), ClientAdres.bepaal("10.128.3.4", listOf("[2001:db8::1]:443"), proxy))
        assertEquals(sleutel("2001:db8::1"), ClientAdres.bepaal("10.128.3.4", listOf("[2001:db8::1]"), proxy))
        assertEquals("10.0.0.1", ClientAdres.bepaal("10.128.3.4", listOf("::ffff:10.0.0.1"), proxy))
        assertEquals(ClientAdres.ONBEKEND, ClientAdres.bepaal("10.128.3.4", listOf("198.51.100.7, "), proxy))
    }

    @Test
    fun `zonder vertrouwde proxy telt het adres van de peer, ook als er een X-Forwarded-For meekomt`() {
        assertEquals("192.0.2.1", bepaal("192.0.2.1", "198.51.100.7", proxy))
        assertEquals("10.128.3.4", bepaal("10.128.3.4", "198.51.100.7", emptyList()))
        assertEquals("10.128.3.4", bepaal("10.128.3.4", null, proxy))
        assertEquals(ClientAdres.ONBEKEND, bepaal(null, "198.51.100.7", proxy))
    }

    @Test
    fun `een netwerk dekt precies zijn prefix, per adresfamilie`() {
        val v6 = ClientAdres.Netwerk.van("2001:db8::/32")
        val host = ClientAdres.Netwerk.van("127.0.0.1")

        assertEquals("198.51.100.7", bepaal("2001:db8:ffff::1", "198.51.100.7", listOf(v6)))
        assertEquals(sleutel("2001:db9::1"), bepaal("2001:db9::1", "198.51.100.7", listOf(v6)))
        assertEquals("10.131.255.255", bepaal("10.131.255.255", "198.51.100.7", emptyList()))
        assertEquals("198.51.100.7", bepaal("10.131.255.255", "198.51.100.7", proxy))
        assertEquals("10.132.0.0", bepaal("10.132.0.0", "198.51.100.7", proxy))
        assertEquals("198.51.100.7", bepaal("127.0.0.1", "198.51.100.7", listOf(host)))
        assertEquals("127.0.0.2", bepaal("127.0.0.2", "198.51.100.7", listOf(host)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "geen-ip/8", "10.0.0.0/33", "10.0.0.0/-1", "10.0.0.0/x", "::/129"])
    fun `een onbruikbaar netwerk in de configuratie wordt geweigerd`(tekst: String) {
        assertThrows(IllegalArgumentException::class.java) { ClientAdres.Netwerk.van(tekst) }
    }
}
