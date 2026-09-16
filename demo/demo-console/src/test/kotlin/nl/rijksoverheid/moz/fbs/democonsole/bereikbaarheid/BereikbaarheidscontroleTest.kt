package nl.rijksoverheid.moz.fbs.democonsole.bereikbaarheid

import com.sun.net.httpserver.HttpServer
import nl.rijksoverheid.moz.fbs.democonsole.bereikbaarheid.Bereikbaarheidscontrole.Companion.HEALTH_PAD
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Tegen een echte HTTP-server in het testproces en niet tegen een mock van de client: wat hier
 * getoetst wordt — welk pad er opgevraagd wordt, wat een hangend of dichtgezet component oplevert,
 * dat trage componenten elkaar niet ophouden — zit juist in het netwerkgedrag van die client.
 *
 * Het eerste padsegment kiest het gedrag: `gereed`, `status-<code>`, `hangt` of `wisselend`.
 */
class BereikbaarheidscontroleTest {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)
    private val draden: ExecutorService = Executors.newCachedThreadPool()
    private val loslaten = CountDownLatch(1)
    private val opgevraagd = CopyOnWriteArrayList<String>()
    private val wisselend = AtomicInteger(OK)

    @BeforeEach
    fun start() {
        server.executor = draden

        server.createContext("/") { uitwisseling ->
            val pad = uitwisseling.requestURI.path

            opgevraagd += pad

            val gedrag = pad.substringAfter('/').substringBefore('/')

            val status = when {
                !pad.endsWith(HEALTH_PAD) -> NIET_GEVONDEN
                gedrag == "wisselend" -> wisselend.get()
                gedrag.startsWith("status-") -> gedrag.removePrefix("status-").toInt()

                gedrag == "hangt" -> {
                    loslaten.await(1, TimeUnit.MINUTES)
                    OK
                }

                else -> OK
            }

            // Sluiten zonder antwoord breekt de verbinding af: de client krijgt dan geen status, maar
            // een fout.
            if (status != AFGEBROKEN) uitwisseling.sendResponseHeaders(status, -1)

            uitwisseling.close()
        }

        server.start()
    }

    @AfterEach
    fun stop() {
        loslaten.countDown()
        server.stop(0)
        draden.shutdownNow()
    }

    @Test
    fun `elk component krijgt zijn eigen toestand, gesorteerd op naam`() {
        val controle = Bereikbaarheidscontrole(
            mapOf(
                "uitvraag" to adres("hangt"),
                "magazijn-b" to adres("status-503"),
                "simulator" to dichtAdres(),
                "magazijn-a" to adres("gereed"),
            ),
            KORT,
        )

        val uitkomst = controle.controleer()

        assertEquals(listOf("magazijn-a", "magazijn-b", "simulator", "uitvraag"), uitkomst.keys.toList())
        assertEquals(
            mapOf(
                "magazijn-a" to Bereikbaarheid.BEREIKBAAR,
                "magazijn-b" to Bereikbaarheid.NIET_GEREED,
                "simulator" to Bereikbaarheid.ONBEREIKBAAR,
                "uitvraag" to Bereikbaarheid.ONBEREIKBAAR,
            ),
            uitkomst,
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3])
    fun `het antwoord noemt precies de ingerichte componenten`(aantal: Int) {
        val adressen = (1..aantal).associate { "component-$it" to adres("gereed") }

        assertEquals(
            adressen.keys.associateWith { Bereikbaarheid.BEREIKBAAR },
            Bereikbaarheidscontrole(adressen, KORT).controleer(),
        )
    }

    /**
     * Een 302 is een inlogpagina die ertussen komt, een 404 een adres waar geen health-pad achter
     * zit: in beide gevallen antwoordt er iets, maar niet het component dat hier gezond moet zijn.
     */
    @ParameterizedTest
    @ValueSource(ints = [302, 401, 404, 500, 503])
    fun `elk ander antwoord dan 200 telt als niet gereed`(status: Int) {
        assertEquals(
            mapOf("component" to Bereikbaarheid.NIET_GEREED),
            Bereikbaarheidscontrole(mapOf("component" to adres("status-$status")), KORT).controleer(),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "/"])
    fun `de controle vraagt het readiness-pad onder het adres op`(staart: String) {
        Bereikbaarheidscontrole(mapOf("component" to adres("gereed") + staart), KORT).controleer()

        assertEquals(listOf("/gereed$HEALTH_PAD"), opgevraagd)
    }

    @Test
    fun `hangende componenten houden elkaar niet op`() {
        val adressen = (1..4).associate { "component-$it" to adres("hangt") }
        // Vóór de meting: het bouwen van de client laadt op een koude JVM de TLS-inrichting, en dat
        // is niet waar deze test over gaat.
        val controle = Bereikbaarheidscontrole(adressen, Duration.ofSeconds(1))
        val begin = System.nanoTime()

        val uitkomst = controle.controleer()

        val verstreken = Duration.ofNanos(System.nanoTime() - begin)

        assertEquals(adressen.keys.associateWith { Bereikbaarheid.ONBEREIKBAAR }, uitkomst)
        // Na elkaar duurt dit minstens vier seconden; daaronder blijven kan alleen als ze tegelijk
        // lopen. Dat moet ook: het paneel breekt een uitlezing na vier seconden af.
        assertTrue(verstreken < Duration.ofSeconds(3), "vier hangende componenten duurden $verstreken")
    }

    @ParameterizedTest
    @ValueSource(strings = ["localhost:8090", "ftp://localhost:8090", "http://", "geen adres"])
    fun `een onbruikbaar adres faalt bij het opstarten en noemt het component`(adres: String) {
        // Anders staat het component de hele demo op onbereikbaar, en zoekt de bediener naar een
        // storing in plaats van naar een typefout in de configuratie.
        val fout = assertThrows<IllegalArgumentException> { Bereikbaarheidscontrole(mapOf("magazijn-a" to adres)) }

        assertTrue("magazijn-a" in fout.message.orEmpty(), fout.message)
    }

    @Test
    fun `alleen een wisseling van toestand komt in het log`() {
        // Het paneel vraagt dit elke vijf seconden op. Een component dat een uur plat ligt hoort
        // niet zevenhonderd keer dezelfde regel op te leveren, en een gezonde start helemaal geen.
        val controle = Bereikbaarheidscontrole(mapOf("magazijn-a" to adres("wisselend")), KORT)

        val bijStart = vangLogregels { controle.controleer() }

        wisselend.set(503)

        val bijUitval = vangLogregels { repeat(3) { controle.controleer() } }

        wisselend.set(OK)

        val bijHerstel = vangLogregels { repeat(2) { controle.controleer() } }

        assertEquals(emptyList<Level>(), bijStart.map { it.level })
        assertEquals(listOf(Level.WARNING), bijUitval.map { it.level })
        assertTrue("magazijn-a" in bijUitval.single().message, bijUitval.single().message)
        assertEquals(listOf(Level.INFO), bijHerstel.map { it.level })
    }

    @Test
    fun `een component dat al bij de eerste ronde plat ligt komt meteen in het log`() {
        val controle = Bereikbaarheidscontrole(mapOf("magazijn-a" to adres("status-503")), KORT)

        val regels = vangLogregels { controle.controleer() }

        assertEquals(listOf(Level.WARNING), regels.map { it.level })
    }

    @Test
    fun `een wisseling tussen twee foute toestanden komt ook in het log`() {
        // Van niet gereed naar onbereikbaar vraagt een ander vervolg: eerst antwoordde het proces nog,
        // nu is het er niet meer.
        val controle = Bereikbaarheidscontrole(mapOf("magazijn-a" to adres("wisselend")), KORT)

        val regels = vangLogregels {
            wisselend.set(503)
            assertEquals(Bereikbaarheid.NIET_GEREED, controle.controleer().getValue("magazijn-a"))

            wisselend.set(AFGEBROKEN)
            assertEquals(Bereikbaarheid.ONBEREIKBAAR, controle.controleer().getValue("magazijn-a"))
        }

        assertEquals(listOf(Level.WARNING, Level.WARNING), regels.map { it.level })
    }

    private fun adres(gedrag: String) = "http://${LOOPBACK.hostAddress}:${server.address.port}/$gedrag"

    /** Een poort die net nog vrij was en waar nu niets meer op luistert. */
    private fun dichtAdres(): String {
        val poort = ServerSocket(0, 0, LOOPBACK).use { it.localPort }

        return "http://${LOOPBACK.hostAddress}:$poort"
    }

    private fun vangLogregels(actie: () -> Unit): List<LogRecord> {
        val logger = Logger.getLogger(Bereikbaarheidscontrole::class.java.name)
        val gevangen = CopyOnWriteArrayList<LogRecord>()

        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                gevangen += record
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

        logger.addHandler(handler)

        try {
            actie()
        } finally {
            logger.removeHandler(handler)
        }

        return gevangen
    }

    private companion object {

        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

        /** Kort genoeg voor een snelle suite, ruim genoeg voor een belaste CI-runner op loopback. */
        val KORT: Duration = Duration.ofMillis(500)

        const val OK = 200
        const val NIET_GEVONDEN = 404

        /** Voor `wisselend`: geen antwoord sturen maar de verbinding verbreken. */
        const val AFGEBROKEN = 0
    }
}
