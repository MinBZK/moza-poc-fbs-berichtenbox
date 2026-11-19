package nl.rijksoverheid.moz.fbs.kern.testhulp

import java.text.MessageFormat
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Vangt elke logregel op en geeft er alles van terug wat een log-backend kan uitschrijven: de
 * melding, de losse parameters en de stacktrace van `thrown`. De eigen code ([categorie]) staat
 * daarbij op het laagste niveau, zodat ook een debug-regel meetelt; de rest logt op zijn gewone
 * niveau, tenzij hij in [open] staat.
 *
 * De parameters apart, omdat een backend die anders formatteert dan deze test toch elke parameter
 * uitschrijft; een waarde die alleen in een parameter staat, staat dus ook in de log.
 *
 * De categorieën van Quarkus REST in [QUARKUS_REST_MET_INVOER] staan standaard níét open. De test-JVM
 * draait op de `LogManager` van de JDK, en daar past Quarkus zijn logconfiguratie niet toe; open
 * gezet zou de vanger een lek melden dat de klem in `application.properties` in een draaiende dienst
 * tegenhoudt. Juist dat maakt [open] bruikbaar als canary: wat er dan lekt, moet uit een geklemde
 * categorie komen.
 */
class Logvanger(categorie: String = "nl.rijksoverheid.moz", open: List<String> = emptyList()) : AutoCloseable {

    private val root: Logger = Logger.getLogger("")
    private val opengezet: List<Logger> = (listOf(categorie) + open).map { Logger.getLogger(it) }
    private val vorigeNiveaus: List<Level?> = opengezet.map { it.level }
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
        opengezet.forEach { it.level = Level.ALL }
        root.addHandler(handler)
    }

    /**
     * Alles wat de opgevangen regels kunnen uitschrijven, als één tekst. Een regel met losse
     * parameters (`infov`) staat er ook opgemaakt in, zodat een test op de vorm van de regel kan
     * toetsen.
     */
    fun tekst(): String = opgevangen().joinToString("\n") { weergave(it) }

    /**
     * De categorieën die iets uit [fragmenten] uitschreven. De kant van de testclient
     * (`org.apache.http`) telt niet mee: die logt het eigen verzoek van de test.
     */
    fun bronnenVan(fragmenten: List<String>): Set<String> = opgevangen()
        .filterNot { it.loggerName.orEmpty().startsWith("org.apache.http") }
        .filter { regel -> weergave(regel).let { tekst -> fragmenten.any(tekst::contains) } }
        .map { it.loggerName.orEmpty() }
        .toSet()

    private fun opgevangen(): List<LogRecord> = synchronized(regels) { regels.toList() }

    private fun weergave(regel: LogRecord): String = listOfNotNull(
        regel.message,
        regel.parameters?.let { parameters -> runCatching { MessageFormat.format(regel.message, *parameters) }.getOrNull() },
        regel.parameters?.joinToString(" | ") { "$it" },
        regel.thrown?.stackTraceToString(),
    ).joinToString("\n")

    override fun close() {
        root.removeHandler(handler)
        opengezet.zip(vorigeNiveaus).forEach { (logger, niveau) -> logger.level = niveau }
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

/**
 * De categorieën van Quarkus REST die een onleesbare body of een niet te converteren parameter op
 * DEBUG of TRACE loggen met de exceptie zelf, en daarmee de invoer. Elke dienst die JSON met een
 * ontvanger aanneemt, zet ze vast op INFO, met `level` en `min-level`.
 */
val QUARKUS_REST_MET_INVOER = listOf(
    "org.jboss.resteasy.reactive.server.handlers.RequestDeserializeHandler",
    "org.jboss.resteasy.reactive.server.handlers.ParameterHandler",
    "org.jboss.resteasy.reactive.common.core.AbstractResteasyReactiveContext",
    "WebApplicationException",
)

/**
 * De categorieën waarin de REST-client verzoek en antwoord logt, op INFO; diensten met een client
 * zetten ze vast op WARN.
 */
val REST_CLIENT_LOGGING = listOf(
    "org.jboss.resteasy.reactive.client.logging",
    "org.jboss.resteasy.reactive.client.logging.DefaultClientLogger",
)

/** Wat een canary openzet: de root, zodat ook een categorie met een kale naam meetelt. */
val ALLES = listOf("")
