package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Een magazijn dat een bijlage in stukken kan geven, kan vasthouden en kan afbreken. WireMock kan
 * een body niet halverwege laten wachten of afkappen; met deze stub kan een test bewijzen dat de
 * uitvraag streamt in plaats van bufferen, en wat er bij een afgebroken overdracht gebeurt.
 *
 * Een eigen OIN ([OIN]) en dus eigen config-sleutels, zodat hij naast [WireMockBackendsResource]
 * kan bestaan zonder dezelfde sleutels te claimen.
 */
class BijlageMagazijnStub : QuarkusTestResourceLifecycleManager {

    /** Hoe een verzoek afliep, gezien vanaf het magazijn. */
    enum class Afloop { VOLLEDIG_GESCHREVEN, SCHRIJVEN_MISLUKT, AFGEBROKEN }

    /** Door [gedrag] gegooid om de connection te verbreken zonder het antwoord af te ronden. */
    class Afbreken : RuntimeException("stub breekt de connection af")

    companion object {
        const val OIN = "00000009900000000572"

        private val GEEN_GEDRAG: (HttpExchange) -> Unit = { it.sendResponseHeaders(404, -1) }

        /** Wat het magazijn doet met het volgende verzoek; de test zet het vóór zijn aanroep. */
        @Volatile
        var gedrag: (HttpExchange) -> Unit = GEEN_GEDRAG

        /** Per afgehandeld verzoek het pad en de [Afloop], zodra de handler klaar is. */
        private val afgehandeld = LinkedBlockingQueue<Pair<String, Afloop>>()

        private lateinit var server: HttpServer
        private lateinit var threads: ExecutorService

        fun reset() {
            gedrag = GEEN_GEDRAG
            afgehandeld.clear()
        }

        /**
         * Wacht tot het magazijn een verzoek voor [berichtId] heeft afgehandeld, en geeft de afloop;
         * `null` na [seconden]. Op het eigen bericht, zodat een late afloop van een vorige test niet meetelt.
         */
        fun afloopVoor(berichtId: Any, seconden: Long = 10): Afloop? {
            val tot = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconden)

            while (true) {
                val over = tot - System.nanoTime()

                if (over <= 0) return null

                val (pad, afloop) = afgehandeld.poll(over, TimeUnit.NANOSECONDS) ?: return null

                if (berichtId.toString() in pad) return afloop
            }
        }

        /**
         * Stuurt een `200` met [lengte] als `Content-Length`, of zonder lengte (chunked) als die
         * `null` is, en schrijft daarna [schrijf]. Geeft die met een vaste lengte minder bytes, dan
         * sluit de server de connection zonder de rest.
         */
        fun bijlage(lengte: Long?, schrijf: (OutputStream) -> Unit): (HttpExchange) -> Unit = { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/pdf")
            // Bij com.sun.net.httpserver betekent 0 "chunked" en -1 "geen body".
            exchange.sendResponseHeaders(200, if (lengte == 0L) -1 else lengte ?: 0)
            schrijf(exchange.responseBody)
        }
    }

    override fun start(): Map<String, String> {
        threads = Executors.newCachedThreadPool()

        val stub = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        stub.executor = threads

        stub.createContext("/api/v1/berichten/") { exchange ->
            val pad = exchange.requestURI.path
            val afloop = try {
                gedrag(exchange)
                exchange.close()
                Afloop.VOLLEDIG_GESCHREVEN
            } catch (_: IOException) {
                // De uitvraag sloot de connection; in een aantal tests juist de bedoeling.
                runCatching { exchange.close() }
                Afloop.SCHRIJVEN_MISLUKT
            } catch (e: Afbreken) {
                // Geen close: die zou een chunked antwoord netjes afronden. De server verbreekt de
                // connection zelf als de handler gooit.
                afgehandeld.add(pad to Afloop.AFGEBROKEN)

                throw e
            }

            afgehandeld.add(pad to afloop)
        }

        stub.start()
        server = stub

        return mapOf(
            "magazijnen.\"$OIN\".url" to "http://localhost:${stub.address.port}",
            "magazijnen.\"$OIN\".naam" to "Stubmagazijn",
        )
    }

    override fun stop() {
        server.stop(0)
        threads.shutdownNow()
    }
}
