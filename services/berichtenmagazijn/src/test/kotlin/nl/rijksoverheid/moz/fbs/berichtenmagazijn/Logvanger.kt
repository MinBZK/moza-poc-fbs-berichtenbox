package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Vangt elke logregel op en geeft er alles van terug wat een log-backend kan uitschrijven: de
 * melding, de losse parameters en de stacktrace van `thrown`. De eigen code ([categorie]) staat
 * daarbij op het laagste niveau, zodat ook een debug-regel meetelt; de rest logt op zijn gewone
 * niveau.
 *
 * De parameters apart, omdat een backend die anders formatteert dan deze test toch elke parameter
 * uitschrijft; een waarde die alleen in een parameter staat, staat dus ook in de log.
 */
class Logvanger(categorie: String = "nl.rijksoverheid.moz") : AutoCloseable {

    private val root: Logger = Logger.getLogger("")
    private val eigen: Logger = Logger.getLogger(categorie)
    private val vorigNiveau: Level? = eigen.level
    private val regels = mutableListOf<LogRecord>()

    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            synchronized(regels) { regels.add(record) }
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    init {
        handler.level = Level.ALL
        eigen.level = Level.ALL
        root.addHandler(handler)
    }

    /** Alles wat de opgevangen regels kunnen uitschrijven, als één tekst. */
    fun tekst(): String = synchronized(regels) { regels.toList() }.joinToString("\n") { regel ->
        listOfNotNull(
            regel.message,
            regel.parameters?.joinToString(" | ") { it.toString() },
            regel.thrown?.stackTraceToString(),
        ).joinToString("\n")
    }

    override fun close() {
        root.removeHandler(handler)
        eigen.level = vorigNiveau
    }
}

/**
 * [waarde] en elk stuk ervan van [MINIMALE_FRAGMENTLENGTE] tekens of meer, zoals het in een log kan
 * opduiken als een bibliotheek een deel van de invoer citeert.
 */
fun herkenbareDelen(waarde: String): List<String> =
    (MINIMALE_FRAGMENTLENGTE..waarde.length).flatMap { lengte -> waarde.windowed(lengte) }.distinct()

/** Vanaf zes tekens is een stuk van een identificatienummer herkenbaar en geen toeval meer. */
const val MINIMALE_FRAGMENTLENGTE = 6
