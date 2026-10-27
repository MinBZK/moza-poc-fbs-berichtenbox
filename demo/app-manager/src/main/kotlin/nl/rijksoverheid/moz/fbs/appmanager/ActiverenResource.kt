package nl.rijksoverheid.moz.fbs.appmanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.vertx.core.http.HttpServerRequest
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.CookieParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.NewCookie
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService
import nl.rijksoverheid.moz.fbs.rechtstreeks.AfgewezenException
import nl.rijksoverheid.moz.fbs.rechtstreeks.Afwijzing
import nl.rijksoverheid.moz.fbs.rechtstreeks.Identiteit
import nl.rijksoverheid.moz.fbs.rechtstreeks.Sessie
import nl.rijksoverheid.moz.fbs.rechtstreeks.Verifier
import org.jboss.logging.Logger
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder

/**
 * Activeren volgens FSC voor Burgers 4.4.1, met de persona-keuze in plaats van DigiD:
 *
 * 1. `CreateSession` met een self-signed certificaat (`/v1/challenge`, `/v1/complete`);
 * 2. `/register_app?session_token=…`, dat het token omwisselt voor een cookie;
 * 3. de keuze van een persona, die een `registration_token` oplevert, versleuteld onder de
 *    sessiesleutel en in het fragment van de vaste terug-URL;
 * 4. `/credential`, dat op dat token een credential uitgeeft voor de sleutel van die sessie.
 *
 * Bodies worden hier zelf gelezen in plaats van door JAX-RS: een kapotte invoer hoort een `400` of
 * `401` te geven, geen `500` uit de parser van het framework.
 */
@Path("/")
class ActiverenResource(
    private val verifier: Verifier,
    private val registraties: Registraties,
    private val uitgifte: CredentialUitgifte,
    private val personas: PersonaService,
    private val config: AppManagerConfig,
    private val json: ObjectMapper,
) {

    private val log = Logger.getLogger(ActiverenResource::class.java)

    @POST
    @Path("v1/challenge")
    @Produces(JOSE)
    fun challenge(@Context verzoek: HttpServerRequest): String = verifier.maakChallenge(client(verzoek))

    /** Stap 20 van `CreateSession`; de veldnamen komen uit de standaard. */
    @POST
    @Path("v1/complete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(JOSE)
    fun complete(body: String, @Context verzoek: HttpServerRequest): String {
        val velden = leesJson(body)
        val nonce = velden?.get("nonce")?.takeIf(JsonNode::isTextual)?.asText()
        val presentatie = velden?.get("encrypted_challenge_result")?.takeIf(JsonNode::isTextual)?.asText()

        if (nonce == null || presentatie == null) throw AfgewezenException(Afwijzing.PRESENTATIE_ONVOLLEDIG)

        return verifier.voltooiSelfSigned(nonce, presentatie, client(verzoek)).antwoord
    }

    /**
     * Met `session_token`: omwisselen voor een cookie en doorsturen naar dit adres zonder token, zodat
     * het niet in geschiedenis of logs blijft staan. Zonder: de keuzepagina.
     */
    @GET
    @Path("register_app")
    @Produces(MediaType.TEXT_HTML)
    fun registreer(@QueryParam("session_token") sessieToken: String?, @CookieParam(COOKIE) koppeling: String?): Response {
        if (sessieToken != null) {
            val (sessie, _) = appSessie(sessieToken)
            val id = registraties.koppel(sessieToken, sessie.verlooptOp) ?: throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND)

            return Response.seeOther(URI.create(REGISTER_APP)).cookie(cookie(id, maxAge = -1)).build()
        }

        appSessie(koppeling?.let(registraties::sessieToken) ?: throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND))

        val terug = URI.create(config.terugUrl())
        val csp = "default-src 'none'; form-action 'self' ${terug.scheme}://${terug.authority}; frame-ancestors 'none'; base-uri 'none'"

        return Response.ok(Keuzepagina.html(personas.alle())).header("Content-Security-Policy", csp).build()
    }

    @POST
    @Path("register_app/kies")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun kies(body: String, @CookieParam(COOKIE) koppeling: String?, @Context verzoek: HttpServerRequest): Response {
        val personaId = formulierveld(body, "persona")
        val persona = personas.alle().firstOrNull { it.id == personaId }
            ?: return Response.status(Response.Status.BAD_REQUEST).entity("onbekende persona").type(MediaType.TEXT_PLAIN).build()
        val sessieToken = koppeling?.let(registraties::sessieToken) ?: throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND)
        val (sessie, app) = appSessie(sessieToken)

        // Eerst registreren, dan pas de koppeling verbruiken: bij een volle opslag kan de gebruiker zo
        // opnieuw kiezen in plaats van vast te lopen.
        val token = registraties.registreer(persona.ontvanger, app.thumbprint, client(verzoek), config.registratieGeldig())

        val versleuteld = try {
            verifier.versleutel(sessie, registratiebericht(token))
        } catch (fout: RuntimeException) {
            registraties.vergeet(token)
            throw fout
        }

        if (!registraties.bevestig(koppeling, token)) throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND)

        val terug = URI.create("${config.terugUrl()}#registration_token=$versleuteld")

        return Response.seeOther(terug).cookie(cookie("", maxAge = 0)).build()
    }

    /** Stap 20 tot en met 28: het credential voor de sleutel van deze sessie, als die bij de registratie hoort. */
    @POST
    @Path("credential")
    @Consumes(JOSE)
    @Produces(JOSE)
    fun credential(@HeaderParam("Authorization") sessieToken: String?, body: String): String {
        val (sessie, app) = appSessie(sessieToken ?: throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND))
        val verzoek = leesJson(verifier.ontsleutel(sessie, body))

        val klopt = verzoek != null && verzoek.path("sub").asText() == "registration_token" && verzoek.path("iss").asText() == APP_ISS

        if (verzoek == null || !klopt) throw AfgewezenException(Afwijzing.PRESENTATIE_ONVOLLEDIG)

        val registratie = registraties.registratie(verzoek.path("registration_token").asText())
            ?: throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND)

        // Alleen de sleutel die bij het activeren de persona koos: een gestolen token levert zo geen
        // credential op een andere sleutel op. Een poging daartoe hoort in de log op te vallen.
        if (registratie.thumbprint != app.thumbprint) {
            log.warnf("Een registration_token is aangeboden met een andere sleutel (thumbprint %s)", app.thumbprint)

            throw AfgewezenException(Afwijzing.APPSLEUTEL_ONGELDIG)
        }

        return verifier.versleutel(sessie, uitgifte.teken(app.publicKey, registratie.ontvanger))
    }

    private fun registratiebericht(token: String): String =
        json.writeValueAsString(mapOf("sub" to "registration_token", "iss" to config.issuerUrl(), "registration_token" to token))

    private fun appSessie(token: String): Pair<Sessie, Identiteit.AppSleutel> {
        val sessie = verifier.sessie(token)
        val app = sessie.identiteit as? Identiteit.AppSleutel ?: throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND)

        return sessie to app
    }

    private fun leesJson(tekst: String): JsonNode? = runCatching { json.readTree(tekst) }.getOrNull()?.takeIf(JsonNode::isObject)

    private fun client(verzoek: HttpServerRequest): String = clientSleutel(verzoek.remoteAddress()?.hostAddress())

    private fun cookie(waarde: String, maxAge: Int): NewCookie = NewCookie.Builder(COOKIE)
        .value(waarde)
        .path(REGISTER_APP)
        .httpOnly(true)
        .secure(true)
        // Lax en niet Strict: de eerste navigatie komt van de origin van de app, en een Strict-cookie
        // gaat dan bij de doorverwijzing niet mee. Een POST van een andere site krijgt hem niet.
        .sameSite(NewCookie.SameSite.LAX)
        .maxAge(maxAge)
        .build()

    companion object {
        const val JOSE = "application/jose"
        const val COOKIE = "activatie"
        const val REGISTER_APP = "/register_app"

        /** Vaste `iss` van de app in het `registration_token`-verzoek (4.4.1, stap 19). */
        const val APP_ISS = "bk-connect://app/"

        /**
         * Wie een quotum draagt: het IP-adres van de aanroeper, voor IPv6 per /64, want één aansluiting
         * krijgt daar een hele /64 en zou anders elk quotum omzeilen. Achter een proxy is het het
         * adres dat de vertrouwde proxy doorgeeft.
         */
        fun clientSleutel(adres: String?): String {
            val ip = adres?.let { runCatching { InetAddress.getByName(it) }.getOrNull() } ?: return "onbekend"

            if (ip !is Inet6Address) return ip.hostAddress

            return ip.address.copyOf(IPV6_PREFIX_BYTES).joinToString(":", postfix = "::/64") { "%02x".format(it) }
        }

        private const val IPV6_PREFIX_BYTES = 8

        /** Eén veld uit een formulier, of `null` als het ontbreekt of de encoding kapot is. */
        fun formulierveld(body: String, naam: String): String? = body.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == naam && it.size == 2 }
            ?.let { runCatching { URLDecoder.decode(it[1], Charsets.UTF_8) }.getOrNull() }
    }
}
