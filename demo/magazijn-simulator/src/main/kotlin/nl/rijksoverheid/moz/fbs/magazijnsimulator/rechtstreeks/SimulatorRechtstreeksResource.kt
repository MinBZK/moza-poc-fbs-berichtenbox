package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.api.RechtstreeksOphalenApi
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.api.model.MagazijnsessieVerzoek
import org.jboss.logging.Logger
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * De tweede ingang van een gesimuleerd magazijn: een magazijnsessie op een credential van een App
 * Manager (FSC voor Burgers 4.3.4). Welk magazijn, staat in [MagazijnContext]; de paden komen uit
 * [RechtstreeksOphalenApi]. Wat de Verifier gooit, vertaalt [VerifierFoutMapper].
 */
class SimulatorRechtstreeksResource(
    private val context: MagazijnContext,
    private val verifiers: MagazijnVerifiers,
    private val verzoek: CurrentVertxRequest,
) : RechtstreeksOphalenApi {

    override fun maakChallenge(): String = verifier().maakChallenge(client())

    override fun startMagazijnsessie(magazijnsessieVerzoek: MagazijnsessieVerzoek): String =
        verifier().voltooiMetCredential(magazijnsessieVerzoek.nonce, magazijnsessieVerzoek.encryptedChallengeResult, client()).antwoord

    override fun getRechtstreeksBerichten(page: Int, pageSize: Int): String = nogNiet()

    override fun getRechtstreeksBericht(berichtId: UUID): String = nogNiet()

    private fun verifier() = verifiers.voor(context.magazijn.oin)

    /**
     * Wel de sessie toetsen: een app zonder geldige sessie hoort de `401` te krijgen waarop hij
     * opnieuw begint, ook nu de berichten nog niet te lezen zijn.
     * TODO(#1215): berichten lezen binnen de magazijnsessie.
     */
    private fun nogNiet(): Nothing {
        val token = bearerToken(verzoek.current?.request()?.getHeader(AUTHORIZATION)) ?: throw SessieOntbreektException()

        verifier().sessie(token)

        throw NogNietBeschikbaarException()
    }

    private fun client(): String {
        val request = verzoek.current?.request()
        val forwarded = request?.headers()?.getAll("X-Forwarded-For").orEmpty()
        val sleutel = ClientAdres.bepaal(request?.remoteAddress()?.hostAddress(), forwarded, verifiers.vertrouwdeProxies)

        // Iedereen zonder herkenbaar adres deelt één quotum; meestal wijst dat op een proxy die niet
        // als vertrouwd staat ingesteld. Eén keer melden is genoeg om dat te vinden.
        if (sleutel == ClientAdres.ONBEKEND && onbekendGemeld.compareAndSet(false, true)) {
            log.warnf("Rechtstreeks ophalen: client-adres niet te bepalen (peer %s); deze aanroepers delen één quotum", request?.remoteAddress()?.hostAddress())
        }

        return sleutel
    }

    companion object {
        private val log: Logger = Logger.getLogger(SimulatorRechtstreeksResource::class.java)
        private val onbekendGemeld = AtomicBoolean(false)
        private val BEARER = Regex("^Bearer +([^ ]+) *$", RegexOption.IGNORE_CASE)

        /**
         * Het token uit `Authorization: Bearer <token>` (RFC 6750, 2.1), of `null` zonder dat schema.
         * Het schema is hoofdletterongevoelig (RFC 9110, 11.1); een kaal token zonder schema telt niet.
         */
        internal fun bearerToken(header: String?): String? = header?.let { BEARER.matchEntire(it)?.groupValues?.get(1) }
    }
}
