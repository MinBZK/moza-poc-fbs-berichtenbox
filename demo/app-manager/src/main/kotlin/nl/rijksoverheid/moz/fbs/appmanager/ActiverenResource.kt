package nl.rijksoverheid.moz.fbs.appmanager

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.util.NetUtil
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
import nl.rijksoverheid.moz.fbs.rechtstreeks.Identiteit
import nl.rijksoverheid.moz.fbs.rechtstreeks.Sessie
import nl.rijksoverheid.moz.fbs.rechtstreeks.Verifier
import org.jboss.logging.Logger
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.time.Clock

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
 * `401` te geven, geen `500` uit de parser van het framework. Bij `/v1/complete` is een envelop of
 * JWE die zonder sleutel al niet klopt `400`, en blijft de nonce bruikbaar; alles daarna is `401`.
 * Bij `/credential` gaat de sessie voor: zonder geldige sessie is elke body `401`.
 */
@Path("/")
class ActiverenResource(
    private val verifier: Verifier,
    private val registraties: Registraties,
    private val uitgifte: CredentialUitgifte,
    private val personas: PersonaService,
    private val config: AppManagerConfig,
    private val json: ObjectMapper,
    private val klok: Clock,
) {

    private val log = Logger.getLogger(ActiverenResource::class.java)

    @POST
    @Path("v1/challenge")
    @Produces(JOSE)
    fun challenge(@Context verzoek: HttpServerRequest): String {
        // Een challenge met een sleutel die verloopt voordat de app hem gebruikt, weigert elke app.
        if (!klok.instant().plus(Verifier.NONCE_GELDIG).isBefore(config.sleutelGeldigTot())) throw SleutelVerlopen()

        return verifier.maakChallenge(client(verzoek))
    }

    /** Stap 20 van `CreateSession` (4.3.4.2); de veldnamen komen uit de standaard. */
    @POST
    @Path("v1/complete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(JOSE)
    fun complete(body: String, @Context verzoek: HttpServerRequest): String {
        val velden = leesJson(body)
        val nonce = velden?.get("nonce")?.takeIf(JsonNode::isTextual)?.asText()
        val presentatie = velden?.get("encrypted_challenge_result")?.takeIf(JsonNode::isTextual)?.asText()

        if (nonce == null || presentatie == null) throw EnvelopOngeldig()

        return verifier.voltooiSelfSigned(nonce, presentatie, client(verzoek)).antwoord
    }

    /**
     * Met `session_token`: omwisselen voor een cookie en doorsturen naar dit adres zonder token, zodat
     * het niet in de adresbalk of de geschiedenis blijft staan. Een log van de ingress ziet het eerste
     * verzoek wel; daarom werkt het token hier één keer. Zonder token: de keuzepagina.
     */
    @GET
    @Path("register_app")
    @Produces(MediaType.TEXT_HTML)
    fun registreer(@QueryParam("session_token") sessieToken: String?, @CookieParam(COOKIE) koppeling: String?): Response {
        if (sessieToken != null) {
            val (sessie, _) = appSessie(sessieToken)
            val id = registraties.koppel(sessieToken, sessie.verlooptOp) ?: throw ActivatieAfgewezen(Activatiefout.SESSIE_TOKEN_HERGEBRUIKT)

            // Absoluut uit de configuratie: een relatief adres neemt schema en host van het verzoek over,
            // en achter een ingress die TLS afhandelt is dat http, waar de Secure-cookie niet meegaat.
            return Response.seeOther(URI.create(config.issuerUrl() + REGISTER_APP.trimStart('/'))).cookie(cookie(id, maxAge = -1)).build()
        }

        appSessie(koppeling?.let(registraties::sessieToken) ?: throw ActivatieAfgewezen(Activatiefout.KOPPELING_ONBEKEND))

        return Response.ok(Keuzepagina.html(personas.alle()))
            .header("Content-Security-Policy", Keuzepagina.csp(origin(config.terugUrl())))
            .build()
    }

    /**
     * De keuze. Alleen van de eigen keuzepagina: de cookie is `SameSite=Lax`, en dat geldt per site,
     * niet per origin. Een andere host op hetzelfde domein kan dus een formulier hierheen sturen;
     * de `Origin`-header houdt dat tegen.
     */
    @POST
    @Path("register_app/kies")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun kies(
        body: String,
        @CookieParam(COOKIE) koppeling: String?,
        @HeaderParam("Origin") herkomst: String?,
        @Context verzoek: HttpServerRequest,
    ): Response {
        if (herkomst != origin(config.issuerUrl())) throw ActivatieAfgewezen(Activatiefout.ANDERE_ORIGIN)

        val personaId = formulierveld(body, "persona")
        val persona = personas.alle().firstOrNull { it.id == personaId } ?: throw EnvelopOngeldig()

        val id = koppeling ?: throw ActivatieAfgewezen(Activatiefout.KOPPELING_ONBEKEND)

        registraties.afgerond(id)?.let { return doorverwijzing(it) }

        // Tussen de vorige regel en deze kan een gelijktijdige submit de koppeling afronden; dan is
        // de doorverwijzing van die submit het juiste antwoord.
        val sessieToken = registraties.sessieToken(id)
            ?: registraties.afgerond(id)?.let { return doorverwijzing(it) }
            ?: throw ActivatieAfgewezen(Activatiefout.KOPPELING_ONBEKEND)
        val (sessie, app) = appSessie(sessieToken)

        // Eerst registreren, dan pas de koppeling verbruiken: bij een volle opslag kan de gebruiker zo
        // opnieuw kiezen in plaats van vast te lopen.
        val token = registraties.registreer(persona.ontvanger, app.thumbprint, client(verzoek), config.registratieGeldig())
        var terug: String? = null

        try {
            val versleuteld = verifier.versleutel(sessie, registratiebericht(token))

            terug = registraties.bevestig(id, token, "${config.terugUrl()}#registration_token=$versleuteld")
        } finally {
            // Elk fouttype, ook een checked exception uit de JOSE-laag: het token bereikte de app niet.
            if (terug == null) registraties.vergeet(token)
        }

        // Een gelijktijdige keuze met dezelfde sleutel kan deze registratie al verdrongen hebben.
        return doorverwijzing(terug ?: throw ActivatieAfgewezen(Activatiefout.KEUZE_NIET_BEVESTIGD))
    }

    private fun doorverwijzing(terug: String): Response =
        Response.seeOther(URI.create(terug)).cookie(cookie("", maxAge = 0)).build()

    /** Stap 20 tot en met 28 van 4.4.1: het credential voor de sleutel van deze sessie, als die bij de registratie hoort. */
    @POST
    @Path("credential")
    @Consumes(JOSE)
    @Produces(JOSE)
    fun credential(@HeaderParam("Authorization") sessieToken: String?, body: String): String {
        // De standaard stuurt het token hier zonder schema; een app die het magazijn nadoet, zet er `Bearer` voor.
        if (sessieToken?.startsWith("Bearer ") == true) log.debug("Authorization met Bearer-schema; de App Manager verwacht het token zonder schema")

        val (sessie, app) = appSessie(sessieToken ?: throw ActivatieAfgewezen(Activatiefout.SESSIE_ONBEKEND))
        val verzoek = leesJson(verifier.ontsleutel(sessie, body))

        val klopt = verzoek != null && verzoek.path("sub").asText() == "registration_token" && verzoek.path("iss").asText() == APP_ISS

        if (verzoek == null || !klopt) throw ActivatieAfgewezen(Activatiefout.VERZOEK_ONGELDIG)

        val registratie = registraties.registratie(verzoek.path("registration_token").asText())
            ?: throw ActivatieAfgewezen(Activatiefout.REGISTRATIE_ONBEKEND)

        // Alleen de sleutel die bij het activeren de persona koos: een gestolen token levert zo geen
        // credential op een andere sleutel op. Een poging daartoe hoort in de log op te vallen, en
        // trekt de registratie niet in: anders kan wie het token steelt, de echte app hinderen.
        if (registratie.thumbprint != app.thumbprint) {
            log.warnf("Een registration_token is aangeboden met een andere sleutel (thumbprint %s)", app.thumbprint)

            throw ActivatieAfgewezen(Activatiefout.ANDERE_SLEUTEL)
        }

        return verifier.versleutel(sessie, uitgifte.onderteken(app.publicKey, registratie.ontvanger))
    }

    private fun registratiebericht(token: String): String =
        json.writeValueAsString(mapOf("sub" to "registration_token", "iss" to config.issuerUrl(), "registration_token" to token))

    private fun appSessie(token: String): Pair<Sessie, Identiteit.AppSleutel> {
        val sessie = verifier.sessie(token)
        val app = sessie.identiteit as? Identiteit.AppSleutel ?: throw ActivatieAfgewezen(Activatiefout.SESSIE_ONBEKEND)

        return sessie to app
    }

    private fun leesJson(tekst: String): JsonNode? = try {
        json.readTree(tekst)?.takeIf(JsonNode::isObject)
    } catch (ignored: JsonProcessingException) {
        null
    }

    private fun client(verzoek: HttpServerRequest): String = clientSleutel(verzoek.remoteAddress()?.hostAddress())

    private fun cookie(waarde: String, maxAge: Int): NewCookie = NewCookie.Builder(COOKIE)
        .value(waarde)
        // __Host- met Path=/: de browser neemt hem alleen aan van deze host, met Secure en zonder
        // Domain, dus een andere host op hetzelfde domein kan hem niet planten.
        .path("/")
        .httpOnly(true)
        .secure(true)
        // Lax en niet Strict: de eerste navigatie komt van de origin van de app, en een Strict-cookie
        // gaat dan bij de doorverwijzing niet mee. Een POST van een andere site krijgt hem niet.
        .sameSite(NewCookie.SameSite.LAX)
        .maxAge(maxAge)
        .build()

    companion object {
        const val JOSE = "application/jose"
        const val COOKIE = "__Host-activatie"
        const val REGISTER_APP = "/register_app"

        /** Vaste `iss` van de app in het `registration_token`-verzoek (4.4.1, stap 19). */
        const val APP_ISS = "bk-connect://app/"

        /** `scheme://host[:poort]` van een absolute URL, zoals een browser hem in `Origin` zet. */
        fun origin(adres: String): String = URI.create(adres).let { "${it.scheme}://${it.rawAuthority}" }

        /**
         * Wie een quotum draagt: het IP-adres van de aanroeper, voor IPv6 per /64, want één aansluiting
         * krijgt daar een hele /64 en zou anders elk quotum omzeilen. Achter een proxy is het het
         * adres dat de vertrouwde proxy doorgeeft. Alles wat geen IP-literal is, deelt het quotum
         * `onbekend`.
         */
        fun clientSleutel(adres: String?): String {
            // Alleen een IP-literal: InetAddress.getByName zou op een naam een DNS-lookup doen.
            val bytes = adres?.let(NetUtil::createByteArrayFromIpAddressString) ?: return "onbekend"
            val ip = InetAddress.getByAddress(bytes)

            if (ip !is Inet6Address) return ip.hostAddress

            return ip.address.copyOf(IPV6_PREFIX_BYTES).joinToString(":", postfix = "::/64") { "%02x".format(it) }
        }

        private const val IPV6_PREFIX_BYTES = 8

        /** Eén veld uit een formulier, of `null` als het ontbreekt of de encoding kapot is. */
        fun formulierveld(body: String, naam: String): String? = body.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == naam && it.size == 2 }
            ?.let { veld ->
                try {
                    URLDecoder.decode(veld[1], Charsets.UTF_8)
                } catch (ignored: IllegalArgumentException) {
                    null
                }
            }
    }
}
