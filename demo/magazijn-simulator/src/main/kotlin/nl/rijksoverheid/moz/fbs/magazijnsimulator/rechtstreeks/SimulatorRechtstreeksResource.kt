package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import io.netty.util.NetUtil
import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.api.RechtstreeksOphalenApi
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.api.model.CompleteVerzoek
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID

/**
 * De tweede ingang van een gesimuleerd magazijn: een magazijnsessie op een credential van een App
 * Manager (FSC voor Burgers 4.3.4). Welk magazijn, staat in [MagazijnContext]; de paden komen uit
 * [RechtstreeksOphalenApi]. Een afwijzing en een volle opslag worden `401` en `503` in
 * [RechtstreeksFoutMappers].
 */
class SimulatorRechtstreeksResource(
    private val context: MagazijnContext,
    private val verifiers: MagazijnVerifiers,
    private val verzoek: CurrentVertxRequest,
) : RechtstreeksOphalenApi {

    override fun maakChallenge(): String = verifier().maakChallenge(client())

    override fun voltooiSessie(completeVerzoek: CompleteVerzoek): String =
        verifier().voltooiMetCredential(completeVerzoek.nonce, completeVerzoek.encryptedChallengeResult, client()).antwoord

    // TODO(#1215): berichten lezen binnen de magazijnsessie.
    override fun getRechtstreeksBerichten(page: Int, pageSize: Int): String = nogNiet()

    override fun getRechtstreeksBericht(berichtId: UUID): String = nogNiet()

    private fun verifier() = verifiers.voor(context.magazijn.oin)

    private fun client(): String = clientSleutel(verzoek.current?.request()?.remoteAddress()?.hostAddress())

    private fun nogNiet(): Nothing = throw WebApplicationException(Response.Status.NOT_IMPLEMENTED)

    companion object {

        /**
         * Wie een quotum draagt: het IP-adres van de aanroeper, voor IPv6 per /64, want één
         * aansluiting krijgt daar een hele /64. Alleen een IP-literal; op een naam zou
         * `InetAddress` een DNS-lookup doen.
         */
        fun clientSleutel(adres: String?): String {
            val bytes = adres?.let(NetUtil::createByteArrayFromIpAddressString) ?: return "onbekend"
            val ip = InetAddress.getByAddress(bytes)

            if (ip !is Inet6Address) return ip.hostAddress

            return ip.address.copyOf(IPV6_PREFIX_BYTES).joinToString(":", postfix = "::/64") { "%02x".format(it) }
        }

        private const val IPV6_PREFIX_BYTES = 8
    }
}
