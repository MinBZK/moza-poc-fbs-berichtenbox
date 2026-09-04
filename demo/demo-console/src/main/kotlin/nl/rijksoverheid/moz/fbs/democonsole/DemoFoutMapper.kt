package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Geeft elke fout een leesbare JSON-body. Deze module heeft bewust geen `fbs-common`-dependency
 * (die trekt de LDV-filters mee), dus zonder deze mapper wordt élke fout een lege 500 en toont het
 * bedieningspaneel voor iedere storing dezelfde onbruikbare regel. Tijdens een demo moet juist
 * binnen seconden zichtbaar zijn wát er stuk is.
 *
 * De melding gaat onverkort naar de client. Lokaal luistert de console alleen op loopback; op de
 * gedeelde omgeving staat hij achter Keycloak-SSO, dus in beide gevallen leest alleen een bediener
 * mee die de tekst nodig heeft. Geen RFC 9457: die vorm hoort bij de productie-API's, niet bij dit
 * paneel.
 */
@Provider
class DemoFoutMapper : ExceptionMapper<Exception> {

    private val log = Logger.getLogger(DemoFoutMapper::class.java.name)

    override fun toResponse(fout: Exception): Response {
        val status = (fout as? WebApplicationException)?.response?.status ?: Response.Status.INTERNAL_SERVER_ERROR.statusCode
        val melding = melding(fout)

        if (status >= Response.Status.INTERNAL_SERVER_ERROR.statusCode) {
            // Mét de throwable en niet als string: de fouten die hier binnenkomen zijn vaak wrappers
            // (een ProcessingException om een ConnectException, bijvoorbeeld), en `toString()` laat
            // juist de oorzaak weg — dan staat er één regel in de log die niets aanwijst.
            log.log(Level.WARNING, "demo-actie mislukt", fout)
        } else {
            // Ook een 4xx krijgt een regel, zonder stacktrace: een bedieningsfout is geen incident,
            // maar zonder log is een weigering achteraf nergens terug te vinden — en dat treft juist
            // de weigeringen die het framework zelf maakt, want die dragen geen eigen uitleg.
            log.info("demo-actie geweigerd (HTTP $status): $melding")
        }

        return Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(mapOf("fout" to melding, "soort" to fout::class.simpleName.orEmpty()))
            .build()
    }

    /**
     * Vult alleen aan waar het framework de melding schreef. JAX-RS geeft een zelfgemaakte
     * weigering — een queryparameter die niet naar zijn type om te zetten is, bijvoorbeeld — de
     * tekst "HTTP 404 Not Found" mee en hangt de werkelijke fout eronder als cause. Zonder die
     * aanvulling leest de bediener een status die hij al kende.
     *
     * Alleen het type van de cause en niet zijn melding: die draagt bij een mislukte omzetting de
     * ingevoerde waarde, en wat een bediener intypt hoort niet in een applicatielog.
     */
    private fun melding(fout: Exception): String {
        val eigen = fout.message?.takeIf { it.isNotBlank() } ?: fout::class.simpleName.orEmpty()
        val oorzaak = fout.cause ?: return eigen

        return if (eigen.startsWith(FRAMEWORK_MELDING)) "$eigen (oorzaak: ${oorzaak::class.simpleName})" else eigen
    }

    private companion object {

        /** Waarmee een melding begint die JAX-RS zelf schreef, en die dus niets over de oorzaak zegt. */
        const val FRAMEWORK_MELDING = "HTTP "
    }
}
