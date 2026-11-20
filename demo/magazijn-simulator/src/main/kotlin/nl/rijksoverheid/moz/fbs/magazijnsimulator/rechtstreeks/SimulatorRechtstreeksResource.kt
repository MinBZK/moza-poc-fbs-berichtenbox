package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.UriBuilder
import jakarta.ws.rs.core.UriInfo
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.rechtstreeks.RechtstreeksOphalenApi
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.rechtstreeks.model.MagazijnsessieVerzoek
import nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten.BerichtDtoMapper
import nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten.BerichtService
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPad
import nl.rijksoverheid.moz.fbs.rechtstreeks.Identiteit
import nl.rijksoverheid.moz.fbs.rechtstreeks.Sessie
import nl.rijksoverheid.moz.fbs.rechtstreeks.Verifier
import org.jboss.logging.Logger
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * De tweede ingang van een gesimuleerd magazijn: een magazijnsessie op een credential van een App
 * Manager (FSC voor Burgers 4.3.4), en daarbinnen de berichten van de ontvanger uit dat
 * credential. Welk magazijn, staat in [MagazijnContext]; de paden komen uit
 * [RechtstreeksOphalenApi]. Wat de Verifier gooit, vertaalt [VerifierFoutMapper].
 *
 * De berichten komen uit dezelfde [BerichtService] als op de eerste ingang. Verschillen: de
 * ontvanger komt uit de sessie in plaats van uit `X-Ontvanger`, andermans bericht is een `404` in
 * plaats van een `403` ([BerichtService.haalOpVoorOntvanger]), en het antwoord gaat versleuteld
 * onder de AES-sleutel van de sessie de deur uit.
 */
class SimulatorRechtstreeksResource(
    private val context: MagazijnContext,
    private val verifiers: MagazijnVerifiers,
    private val berichten: BerichtService,
    private val json: ObjectMapper,
    private val verzoek: CurrentVertxRequest,
    @param:Context private val uriInfo: UriInfo,
) : RechtstreeksOphalenApi {

    override fun maakChallenge(): String = verifier().maakChallenge(client())

    override fun startMagazijnsessie(magazijnsessieVerzoek: MagazijnsessieVerzoek): String =
        verifier().voltooiMetCredential(magazijnsessieVerzoek.nonce, magazijnsessieVerzoek.encryptedChallengeResult, client()).antwoord

    override fun getRechtstreeksBerichten(page: Int, pageSize: Int): String {
        val (verifier, sessie, ontvanger) = magazijnsessie()
        val pagina = berichten.lijst(ontvanger = ontvanger, afzender = null, page = page, pageSize = pageSize)

        return verifier.versleutel(sessie, json.writeValueAsString(BerichtDtoMapper.naarBerichtenLijst(pagina, null, basis())))
    }

    override fun getRechtstreeksBericht(berichtId: UUID): String {
        val (verifier, sessie, ontvanger) = magazijnsessie()

        val bericht = berichten.haalOpVoorOntvanger(berichtId, ontvanger)

        return verifier.versleutel(sessie, json.writeValueAsString(BerichtDtoMapper.naarBericht(bericht, basis())))
    }

    private fun verifier() = verifiers.voor(context.magazijn.oin)

    private data class Magazijnsessie(val verifier: Verifier, val sessie: Sessie, val ontvanger: Identificatienummer)

    /** De sessie uit `Authorization: Bearer <token>`, met de ontvanger die het credential vaststelde. */
    private fun magazijnsessie(): Magazijnsessie {
        // Een herhaalde header faalt dicht: een proxy die de laatste leest, zou een ander token beoordelen.
        val waarden = verzoek.current?.request()?.headers()?.getAll(AUTHORIZATION).orEmpty()
        val token = waarden.singleOrNull()?.let(::bearerToken) ?: throw SessieOntbreektException()
        val verifier = verifier()
        val sessie = verifier.sessie(token)
        // Een magazijn geeft alleen sessies op een credential uit, dus altijd met een ontvanger.
        val identiteit = sessie.identiteit as? Identiteit.Ontvanger ?: error("magazijnsessie zonder ontvanger bij magazijn ${context.magazijn.oin}")

        return Magazijnsessie(verifier, sessie, identiteit.ontvanger)
    }

    /** De links in de payload wijzen naar de paden van deze ingang. */
    private fun basis(): UriBuilder = MagazijnPad.basisUri(uriInfo, context.magazijn.oin).path(RECHTSTREEKS)

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
        // Gelijk aan de @Path van RechtstreeksOphalenApi; de tests op de links in RechtstreeksBerichtenTest bewaken de koppeling.
        private const val RECHTSTREEKS = "rechtstreeks"

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
