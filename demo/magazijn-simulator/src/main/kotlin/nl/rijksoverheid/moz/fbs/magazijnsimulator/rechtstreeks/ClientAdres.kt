package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import io.netty.util.NetUtil
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Wie een quotum van rechtstreeks ophalen draagt: het IP-adres van de aanroeper, voor IPv6 per /64,
 * want één aansluiting krijgt daar een hele /64.
 *
 * Achter een vertrouwde proxy geldt de láátste waarde van `X-Forwarded-For`: die zette de proxy
 * zelf. Alles ervóór kan de client verzinnen, en de OpenShift-router vult de header standaard aan
 * in plaats van hem te vervangen. Daarom niet `quarkus.http.proxy.proxy-address-forwarding`, dat de
 * eerste waarde neemt.
 */
object ClientAdres {

    const val ONBEKEND = "onbekend"

    fun bepaal(peer: String?, xForwardedFor: String?, vertrouwd: List<Netwerk>): String {
        val direct = ip(peer)
        val achterProxy = direct != null && xForwardedFor != null && vertrouwd.any { it.bevat(direct) }
        val adres = if (achterProxy) ip(xForwardedFor.substringAfterLast(',').trim()) else direct

        return sleutel(adres)
    }

    /** De sleutel voor één adres; alleen een IP-literal telt, want op een naam zou `InetAddress` een DNS-lookup doen. */
    fun clientSleutel(adres: String?): String = sleutel(ip(adres))

    private fun ip(tekst: String?): InetAddress? = tekst?.let(NetUtil::createByteArrayFromIpAddressString)?.let(InetAddress::getByAddress)

    private fun sleutel(ip: InetAddress?): String {
        if (ip == null) return ONBEKEND

        if (ip !is Inet6Address) return ip.hostAddress

        return ip.address.copyOf(IPV6_PREFIX_BYTES).joinToString(":", postfix = "::/64") { "%02x".format(it) }
    }

    private const val IPV6_PREFIX_BYTES = 8

    /** Een netwerk als `adres/prefix`, bijvoorbeeld `10.128.0.0/14`; een los adres telt als één host. */
    class Netwerk private constructor(private val netwerk: ByteArray, private val prefix: Int) {

        fun bevat(adres: InetAddress): Boolean {
            val bytes = adres.address

            if (bytes.size != netwerk.size) return false

            return (0 until prefix).all { bit -> bitVan(bytes, bit) == bitVan(netwerk, bit) }
        }

        private fun bitVan(bytes: ByteArray, bit: Int): Int = (bytes[bit / BITS_PER_BYTE].toInt() shr (BITS_PER_BYTE - 1 - bit % BITS_PER_BYTE)) and 1

        companion object {
            private const val BITS_PER_BYTE = 8

            fun van(tekst: String): Netwerk {
                val adres = ip(tekst.substringBefore('/').trim()) ?: throw IllegalArgumentException("geen IP-adres: $tekst")
                val bits = adres.address.size * BITS_PER_BYTE
                val prefix = if ('/' in tekst) tekst.substringAfter('/').trim().toIntOrNull() else bits

                require(prefix != null && prefix in 0..bits) { "geen geldig prefix: $tekst" }

                return Netwerk(adres.address, prefix)
            }
        }
    }
}
