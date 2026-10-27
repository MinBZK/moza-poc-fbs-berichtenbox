package nl.rijksoverheid.moz.fbs.appmanager

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import io.vertx.core.http.HttpServerRequest
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.CookieParam
import jakarta.ws.rs.FormParam
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
import jakarta.ws.rs.core.UriInfo
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService
import nl.rijksoverheid.moz.fbs.rechtstreeks.AfgewezenException
import nl.rijksoverheid.moz.fbs.rechtstreeks.Afwijzing
import nl.rijksoverheid.moz.fbs.rechtstreeks.Identiteit
import nl.rijksoverheid.moz.fbs.rechtstreeks.Sessie
import nl.rijksoverheid.moz.fbs.rechtstreeks.Verifier
import java.net.URI

/** Stap 20 van `CreateSession`; de veldnamen in de JSON komen uit de standaard. */
data class CompleteVerzoek(
    val nonce: String?,
    @param:JsonProperty("encrypted_challenge_result") val versleuteldResultaat: String?,
)

/**
 * Activeren volgens FSC voor Burgers 4.4.1, met de persona-keuze in plaats van DigiD:
 *
 * 1. `CreateSession` met een self-signed certificaat (`/v1/challenge`, `/v1/complete`);
 * 2. `/register_app?session_token=…`, dat het token omwisselt voor een cookie;
 * 3. de keuze van een persona, die een `registration_token` oplevert, versleuteld onder de
 *    sessiesleutel en in het fragment van de vaste terug-URL;
 * 4. `/credential`, dat op dat token een credential uitgeeft voor de sleutel van die sessie.
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

    @POST
    @Path("v1/challenge")
    @Produces(JOSE)
    fun challenge(@Context verzoek: HttpServerRequest): String = verifier.maakChallenge(client(verzoek))

    @POST
    @Path("v1/complete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(JOSE)
    fun complete(antwoord: CompleteVerzoek?, @Context verzoek: HttpServerRequest): String {
        val nonce = antwoord?.nonce ?: throw AfgewezenException(Afwijzing.PRESENTATIE_ONVOLLEDIG)
        val presentatie = antwoord.versleuteldResultaat ?: throw AfgewezenException(Afwijzing.PRESENTATIE_ONVOLLEDIG)

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
            val sessie = appSessie(sessieToken)
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
    fun kies(@FormParam("persona") personaId: String?, @CookieParam(COOKIE) koppeling: String?, @Context uri: UriInfo): Response {
        val persona = personas.alle().firstOrNull { it.id == personaId }
            ?: return Response.status(Response.Status.BAD_REQUEST).entity("onbekende persona").type(MediaType.TEXT_PLAIN).build()
        val sessie = appSessie(koppeling?.let(registraties::neemKoppeling) ?: throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND))
        val app = sessie.identiteit as Identiteit.AppSleutel
        val token = registraties.registreer(persona.ontvanger, app.thumbprint, config.registratieGeldig())

        val bericht = json.writeValueAsString(
            mapOf("sub" to "registration_token", "iss" to uri.baseUri.toString(), "registration_token" to token),
        )

        val terug = URI.create("${config.terugUrl()}#registration_token=${verifier.versleutel(sessie, bericht)}")

        return Response.seeOther(terug).cookie(cookie("", maxAge = 0)).build()
    }

    /** Stap 20 tot en met 28: het credential voor de sleutel van deze sessie, als die bij de registratie hoort. */
    @POST
    @Path("credential")
    @Consumes(JOSE)
    @Produces(JOSE)
    fun credential(@HeaderParam("Authorization") sessieToken: String?, lichaam: String): String {
        val sessie = appSessie(sessieToken ?: throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND))
        val app = sessie.identiteit as Identiteit.AppSleutel
        val tekst = verifier.ontsleutel(sessie, lichaam)
        val verzoek = runCatching { json.readTree(tekst) }.getOrNull()

        val klopt = verzoek != null && verzoek.path("sub").asText() == "registration_token" && verzoek.path("iss").asText() == APP_ISS
        val registratie = verzoek?.path("registration_token")?.asText()?.takeIf { klopt }?.let(registraties::registratie)

        // Alleen de sleutel die bij het activeren de persona koos: een gestolen token levert zo geen
        // credential op een andere sleutel op.
        if (registratie == null || registratie.thumbprint != app.thumbprint) throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND)

        return verifier.versleutel(sessie, uitgifte.teken(app.publicKey, registratie.ontvanger))
    }

    private fun appSessie(token: String): Sessie {
        val sessie = verifier.sessie(token)

        if (sessie.identiteit !is Identiteit.AppSleutel) throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND)

        return sessie
    }

    /** Het adres van de verbinding; achter een proxy het adres dat de vertrouwde proxy doorgeeft. */
    private fun client(verzoek: HttpServerRequest): String = verzoek.remoteAddress()?.hostAddress() ?: "onbekend"

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
    }
}
