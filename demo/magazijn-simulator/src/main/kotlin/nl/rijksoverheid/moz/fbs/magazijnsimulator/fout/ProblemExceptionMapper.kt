package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Problem
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Zorgt dat elke [WebApplicationException] als `problem+json` bij de client aankomt.
 *
 * Draagt de exception al een Problem-response — zoals de resources die zelf opbouwen — dan gaat die
 * ongewijzigd door. Alleen voor exceptions die ergens anders vandaan komen (Quarkus zelf, een
 * bibliotheek) wordt er alsnog een Problem omheen gezet, zodat er geen HTML-foutpagina of lege body
 * naar buiten lekt.
 *
 * Deze mapper is bovendien nodig omdat [UncaughtExceptionMapper] op `Throwable` staat: zonder een
 * specifiekere mapper voor [WebApplicationException] zou die het vangnet in werking stellen en zou
 * een bewuste 404 als 500 bij de client aankomen. Keerzijde: een 5xx die als
 * `WebApplicationException` binnenkomt, bereikt dat vangnet dan óók niet — vandaar dat die tak hier
 * zelf logt. Een 500 zonder logregel is in een demo met honderd magazijnen niet te herleiden.
 */
@Provider
class ProblemExceptionMapper : ExceptionMapper<WebApplicationException> {

    private val log = Logger.getLogger(ProblemExceptionMapper::class.java)

    override fun toResponse(exception: WebApplicationException): Response {
        val response = exception.response

        if (response.entity is Problem) return response

        val status = response.status
        val title = Response.Status.fromStatusCode(status)?.reasonPhrase ?: "Error"
        val foutId = UUID.randomUUID()

        // Een throw-site die de situatie kent geeft zijn eigen kenmerk mee; de rest krijgt de
        // terugval op de status.
        val foutcode = (exception as? SimulatorFout)?.foutcode ?: Foutcode.voorStatus(status)

        if (status >= SERVERFOUT_VANAF) {
            // De melding blijft uit de log: bij een exception van elders kan die gebruikersinvoer
            // dragen. Het exception-object levert de stack, en het correlatie-id koppelt log en
            // antwoord aan elkaar.
            log.errorf(exception, "Serverfout %d (foutId=%s, type=%s)", status, foutId, exception.javaClass.name)

            // Bij een 5xx gaat de melding niet naar de client: die kan interne details dragen, en er
            // is hier niets dat dat nog kan onderscheiden.
            return problemResponse(status = status, title = title, foutId = foutId, foutcode = foutcode)
        }

        // Ook een clientfout krijgt een regel, op INFO. Het antwoord draagt een correlatie-id, en een
        // id dat nergens in de log staat is geen correlatie maar een sierletter. De melding blijft
        // eruit om dezelfde reden als hierboven: die kan invoer dragen.
        log.infof("Clientfout %d (foutId=%s, type=%s)", status, foutId, exception.javaClass.name)

        return problemResponse(
            status = status,
            title = title,
            detail = veiligDetail(exception.message),
            foutId = foutId,
            foutcode = foutcode,
        )
    }

    /**
     * Bij een 4xx is de melding juist nuttig — "Bericht X bestaat niet in magazijn Y" is precies wat
     * een aanroeper nodig heeft. Alleen niet als hij eruitziet als interne toestand: een stacktrace
     * of een bestandsverwijzing hoort nooit een client te bereiken, en aan een exception van elders
     * is niet te zien waar zijn melding vandaan komt.
     */
    private fun veiligDetail(melding: String?): String? {
        val opgeschoond = melding?.filter { it.code >= EERSTE_LEESBARE_TEKEN }?.trim()

        if (opgeschoond.isNullOrBlank()) return null

        // De standaardmelding van het framework ("HTTP 400 Bad Request") voegt niets toe aan `title`
        // en `status`, en zet er een Engelse zin in een verder Nederlandstalig antwoord. Beter geen
        // `detail` dan die.
        if (STANDAARDMELDING.matches(opgeschoond)) return null

        return if (INTERNE_SPOREN.containsMatchIn(opgeschoond)) null else opgeschoond
    }

    private companion object {
        const val SERVERFOUT_VANAF = 500

        /** Alles onder spatie is een controlteken; die horen niet in een antwoord. */
        const val EERSTE_LEESBARE_TEKEN = 0x20

        /** Wat Quarkus zelf invult als er geen echte melding is. */
        val STANDAARDMELDING = Regex("""HTTP \d{3}( .*)?""")

        /** Stacktrace-achtige inhoud: een `at `-frame of een bron-verwijzing met regelnummer. */
        val INTERNE_SPOREN = Regex("""\bat [\w.$]+\(|\.(java|kt):\d+""")
    }
}
