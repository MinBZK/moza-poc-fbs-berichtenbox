package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.leesUitCache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.splitOntvanger
import nl.rijksoverheid.moz.fbs.common.exception.FbsFoutException
import nl.rijksoverheid.moz.fbs.common.exception.Foutcode
import nl.rijksoverheid.moz.fbs.kern.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.jboss.logging.Logger

/** Wat een verzoek aan de sessie moet laten zien voordat het door mag. */
enum class Sessievereiste {
    /** Geen sessie: het verzoek komt niet van een ingelogde gebruiker, of start juist een sessie. */
    GEEN,

    /** Een goed gevormde `X-Sessie`, maar of die sessie loopt, maakt niet uit: uitloggen is idempotent. */
    ALLEEN_VORM,

    /** Een lopende sessie: een verzoek van de gebruiker. */
    LOPEND,
}

/** De sessie die het filter bij dit verzoek vaststelde. */
class HuidigeSessie(val sessieId: String) {
    // Het id is een credential; een toString belandt makkelijk in een log.
    override fun toString(): String = "HuidigeSessie(sessieId=***)"
}

/**
 * De sessiecontrole van de hele API. Weigert standaard: elk pad vereist een lopende sessie van de
 * ontvanger uit `X-Ontvanger`, behalve de expliciete vrijstellingen in [vereiste]. Een nieuw
 * endpoint is zo beschermd zonder dat iemand eraan hoeft te denken, ook een SSE-endpoint buiten de
 * codegen.
 *
 * Een filter en geen controle per resource: zonder sessie verdient elk endpoint dezelfde 401, en
 * de gegenereerde validatie zou een kromme `X-Ontvanger` anders pas na deze controle zien. Het
 * filter draait vóór die validatie.
 *
 * Faalt dicht: de controle telt de headerwaarden zelf. `getHeaderString` voegt herhaalde waarden
 * samen met een komma, terwijl `@HeaderParam` de eerste neemt; wie twee keer `X-Ontvanger` stuurt,
 * zou zo langs een controle op de samengevoegde waarde komen en toch een geldige ontvanger in de
 * resource krijgen. Daarom precies één `X-Ontvanger` die als identificatienummer te lezen is (anders
 * 400 `ongeldig-verzoek`, dezelfde vorm als de validatie geeft) en precies één goed gevormde
 * `X-Sessie` (anders 401). Post-matching, zodat het pad het pad is waarop de router het verzoek al
 * heeft gematcht; een omweg als `/aanmeldingen/../berichten` komt hier dus als `/berichten` binnen.
 *
 * Wat het vaststelde, staat voor de resource onder [HUIDIGE_SESSIE]; de sessie-id staat sinds het
 * securityschema niet meer als parameter in de gegenereerde interfaces. De sessie-id is een
 * credential en komt in geen enkele logregel of foutmelding.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
class SessieFilter(private val sessiecache: Sessiecache) : ContainerRequestFilter {

    private val log = Logger.getLogger(SessieFilter::class.java)

    override fun filter(request: ContainerRequestContext) {
        val vereiste = vereiste(request.method, request.uriInfo.path)

        if (vereiste == Sessievereiste.GEEN) return

        val ontvanger = leesOntvanger(request.headers[ONTVANGER_HEADER].orEmpty())
        val sessieId = request.headers[SESSIE_HEADER]?.singleOrNull()

        if (sessieId == null || !Sessiecache.SESSIE_ID_VORM.matches(sessieId)) throw sessieOngeldig()

        if (vereiste == Sessievereiste.LOPEND) leesUitCache(log, "sessie-lezen") { sessiecache.leesSessie(sessieId, ontvanger) }

        request.setProperty(HUIDIGE_SESSIE, HuidigeSessie(sessieId))
    }

    private fun leesOntvanger(waarden: List<String>): Identificatienummer {
        val header = waarden.singleOrNull()

        if (header == null || splitOntvanger(header) == null) throw ontvangerOngeldig()

        return try {
            Identificatienummer.fromHeader(header)
        } catch (e: DomainValidationException) {
            throw ontvangerOngeldig(e)
        }
    }

    // Geen waarde in de melding: die kan een BSN zijn.
    private fun ontvangerOngeldig(oorzaak: Throwable? = null) = FbsFoutException(
        Foutcode.ONGELDIG_VERZOEK,
        Response.Status.BAD_REQUEST,
        "X-Ontvanger: precies één geldig identificatienummer vereist (Type:waarde)",
        oorzaak,
    )

    private fun sessieOngeldig() =
        FbsFoutException(Foutcode.SESSIE_ONGELDIG, Response.Status.UNAUTHORIZED, Foutcode.SESSIE_ONGELDIG.uitleg)

    companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val SESSIE_HEADER = "X-Sessie"

        /** Request-property met de [HuidigeSessie]; staat er bij elk verzoek dat een sessie vereist. */
        const val HUIDIGE_SESSIE = "nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie.HuidigeSessie"

        /**
         * Wat een verzoek moet laten zien, op [methode] en het pad zonder `quarkus.rest.path` (dat
         * JAX-RS met of zonder voorloop-slash levert). Alles wat hier niet als uitzondering staat,
         * vereist een lopende sessie: dat is de veilige standaard voor een nieuw endpoint.
         */
        fun vereiste(methode: String, pad: String): Sessievereiste {
            val relatief = pad.removePrefix("/")

            return when {
                relatief in VRIJGESTELD -> Sessievereiste.GEEN
                relatief == "sessies/huidige" && methode == "DELETE" -> Sessievereiste.ALLEEN_VORM
                else -> Sessievereiste.LOPEND
            }
        }

        /**
         * Paden zonder sessie: een sessie starten, de aanmelding van een magazijn (geen gebruiker), en
         * het OpenAPI-document. Bij `sessies` volstaat het pad, want daar bestaat alleen `POST`.
         */
        private val VRIJGESTELD = setOf("sessies", "aanmeldingen", "openapi.json")
    }
}
