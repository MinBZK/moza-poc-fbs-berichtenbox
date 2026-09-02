package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.DomeinFout
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Vertaalt een geschonden domein-invariant naar de 400 `problem+json` die de spec voorschrijft —
 * dezelfde plek waar het echte magazijn zijn `DomainValidationException` afvangt. Zonder deze mapper
 * zou het vangnet op `Exception` toeslaan en een clientfout een 500 worden.
 *
 * De melding gaat mee naar de client. Dat kan omdat de meldingen bij constructie zo geschreven zijn
 * dat ze de aangeboden waarde nooit echoën: `X-Ontvanger` kan een BSN dragen, en die hoort nergens
 * in een antwoord of een log te belanden.
 */
@Provider
class DomeinFoutMapper : ExceptionMapper<DomeinFout> {

    private val log = Logger.getLogger(DomeinFoutMapper::class.java)

    override fun toResponse(exception: DomeinFout): Response {
        val foutId = UUID.randomUUID()

        // Op info en niet op debug: een clientfout is geen incident, maar het effectieve niveau is
        // info en een regel die niet uitgezonden wordt maakt het `instance`-id uit het antwoord
        // onvindbaar — en dat is precies waar iemand mee aanklopt. Dezelfde keuze als
        // ProblemExceptionMapper maakt voor zijn 4xx.
        log.infof("Geschonden domein-invariant (foutId=%s)", foutId)

        return problemResponse(
            status = Response.Status.BAD_REQUEST.statusCode,
            title = "Bad Request",
            detail = exception.message,
            foutId = foutId,
        )
    }
}
