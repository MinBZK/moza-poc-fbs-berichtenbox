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

        // Mét de throwable en niet als string: de fouten die hier binnenkomen zijn vaak wrappers
        // (een ProcessingException om een ConnectException, bijvoorbeeld), en `toString()` laat
        // juist de oorzaak weg — dan staat er één regel in de log die niets aanwijst.
        if (status >= Response.Status.INTERNAL_SERVER_ERROR.statusCode) {
            log.log(Level.WARNING, "demo-actie mislukt", fout)
        }

        return Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(mapOf("fout" to (fout.message ?: fout::class.simpleName.orEmpty()), "soort" to fout::class.simpleName.orEmpty()))
            .build()
    }
}
