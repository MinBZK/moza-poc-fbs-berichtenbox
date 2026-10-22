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

/**
 * Laat een verzoek onder `/berichten` alleen door met een lopende sessie van de ontvanger uit
 * `X-Ontvanger`, en raakt die sessie (verlengt hem). Eén plek, zodat geen endpoint onder
 * `/berichten` de controle kan vergeten — ook de SSE-endpoints buiten de codegen niet.
 *
 * Een filter en geen controle per resource: de gegenereerde Bean Validation zou een ontbrekende of
 * krom gevormde `X-Sessie` als 400 weigeren, terwijl de aanroeper elk soort ongeldige sessie als
 * dezelfde 401 hoort te zien. Dit filter draait vóór die validatie.
 *
 * Faalt dicht: de controle telt de headerwaarden zelf. `getHeaderString` voegt herhaalde waarden
 * samen met een komma, terwijl `@HeaderParam` de eerste neemt; wie twee keer `X-Ontvanger` stuurt,
 * zou zo langs een controle op de samengevoegde waarde komen en toch een geldige ontvanger in de
 * resource krijgen. Daarom precies één `X-Ontvanger` die als identificatienummer te lezen is (anders
 * 400 `ongeldig-verzoek`, dezelfde vorm als de validatie geeft) en precies één goed gevormde
 * `X-Sessie` (anders 401). Post-matching, zodat het pad het pad is waarop de router het verzoek al
 * heeft gematcht; een omweg als `/aanmeldingen/../berichten` komt hier dus als `/berichten` binnen.
 *
 * De sessie-id is een credential en komt in geen enkele logregel of foutmelding.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
class SessieFilter(private val sessiecache: Sessiecache) : ContainerRequestFilter {

    private val log = Logger.getLogger(SessieFilter::class.java)

    override fun filter(request: ContainerRequestContext) {
        if (!valtOnderBerichten(request.uriInfo.path)) return

        val ontvanger = leesOntvanger(request.headers[ONTVANGER_HEADER].orEmpty())
        val sessieId = request.headers[SESSIE_HEADER]?.singleOrNull()

        if (sessieId == null || !Sessiecache.SESSIE_ID_VORM.matches(sessieId)) throw sessieOngeldig()

        leesUitCache(log, "sessie-raken") { sessiecache.raakSessie(sessieId, ontvanger) }
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

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val SESSIE_HEADER = "X-Sessie"

        /** Het pad zonder `quarkus.rest.path`; JAX-RS levert het met of zonder voorloop-slash. */
        fun valtOnderBerichten(pad: String): Boolean {
            val relatief = pad.removePrefix("/")

            return relatief == "berichten" || relatief.startsWith("berichten/")
        }
    }
}
