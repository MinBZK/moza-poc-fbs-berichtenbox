package nl.rijksoverheid.moz.fbs.democonsole.tempo

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.generator.DemoBerichtGenerator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

data class TempoStatus(val loopt: Boolean, val intervalSeconden: Int, val geleverd: Int)

/**
 * Een doorlopende stroom nieuwe berichten, één per tik. Draait server-side en niet in de browser:
 * op de gedeelde omgeving zien alle kijkers dan dezelfde stroom, en het sluiten van een tab legt
 * hem niet stil.
 *
 * De bovengrenzen zijn er omdat diezelfde eigenschap ook de keerzijde is — een vergeten stroom
 * blijft anders een weekend lang berichten pompen in een omgeving waar niemand kijkt.
 */
@ApplicationScoped
class TempoService(
    private val klok: TempoKlok,
    private val aanleverService: AanleverService,
    private val generator: DemoBerichtGenerator,
    private val clock: Clock,
) {

    private var interval = 0
    private var geleverd = 0

    /** De bovengrens telt pogingen: een magazijn dat uit staat mag de stroom niet oneindig rekken. */
    private var pogingen = 0
    private var gestartOp: Instant? = null

    @Synchronized
    fun start(intervalSeconden: Int): TempoStatus {
        // BadRequestException en geen require(): DemoFoutMapper vertaalt alleen een
        // WebApplicationException naar zijn eigen status, dus een require() zou een
        // bedieningsfout als 500 tonen.
        if (intervalSeconden !in MIN_INTERVAL..MAX_INTERVAL) {
            throw BadRequestException(
                "interval moet tussen $MIN_INTERVAL en $MAX_INTERVAL seconden liggen, was: $intervalSeconden",
            )
        }

        klok.stop()

        interval = intervalSeconden
        geleverd = 0
        pogingen = 0
        gestartOp = clock.instant()

        klok.start(intervalSeconden) { tik() }

        return status()
    }

    @Synchronized
    fun stop(): TempoStatus {
        klok.stop()

        gestartOp = null

        return status()
    }

    @Synchronized
    fun status(): TempoStatus = TempoStatus(gestartOp != null, interval, geleverd)

    @Synchronized
    internal fun tik() {
        val start = gestartOp ?: return

        if (pogingen >= MAX_BERICHTEN || Duration.between(start, clock.instant()) > MAX_DUUR) {
            stop()

            return
        }

        pogingen++

        // Het aantal afleveringen en niet het aantal tikken: staat een magazijn uit, dan hoort de
        // chip stil te blijven staan in plaats van door te tellen alsof er berichten aankomen.
        geleverd += aanleverService.leverAan(generator.genereer(1, Random.Default)).geslaagd
    }

    companion object {

        const val MIN_INTERVAL = 1
        const val MAX_INTERVAL = 3600
        const val MAX_BERICHTEN = 500

        val MAX_DUUR: Duration = Duration.ofMinutes(60)
    }
}
