package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.api.RechtstreeksOphalenApi
import nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks.api.model.CompleteVerzoek
import nl.rijksoverheid.moz.fbs.rechtstreeks.AfgewezenException
import nl.rijksoverheid.moz.fbs.rechtstreeks.Afwijzing
import org.jboss.logging.Logger
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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

    override fun getRechtstreeksBerichten(page: Int, pageSize: Int): String = nogNiet()

    override fun getRechtstreeksBericht(berichtId: UUID): String = nogNiet()

    private fun verifier() = verifiers.voor(context.magazijn.oin)

    /**
     * Wel de sessie toetsen: een app zonder geldige sessie hoort de `401` te krijgen waarop hij
     * opnieuw begint, ook nu de berichten nog niet te lezen zijn.
     * TODO(#1215): berichten lezen binnen de magazijnsessie.
     */
    private fun nogNiet(): Nothing {
        val token = verzoek.current?.request()?.getHeader("Authorization") ?: throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND)

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

    private companion object {
        val log: Logger = Logger.getLogger(SimulatorRechtstreeksResource::class.java)
        val onbekendGemeld = AtomicBoolean(false)
    }
}
