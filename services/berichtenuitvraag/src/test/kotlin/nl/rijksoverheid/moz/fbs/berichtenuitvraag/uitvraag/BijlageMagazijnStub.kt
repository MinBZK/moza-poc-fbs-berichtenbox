package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Een magazijn dat een bijlage in stukken kan geven, kan vasthouden en kan afbreken. WireMock kan
 * een body niet halverwege laten wachten of afkappen; met deze stub kan een test bewijzen dat de
 * uitvraag streamt in plaats van bufferen, en wat er bij een afgebroken overdracht gebeurt.
 *
 * Een eigen OIN ([OIN]) en dus eigen config-sleutels, zodat hij naast [WireMockBackendsResource]
 * kan bestaan zonder dezelfde sleutels te claimen.
 */
class BijlageMagazijnStub : QuarkusTestResourceLifecycleManager {

    companion object {
        const val OIN = "00000009900000000572"

        /** Wat het magazijn doet met het volgende verzoek; de test zet het vóór zijn aanroep. */
        @Volatile
        var gedrag: (HttpExchange) -> Unit = { it.sendResponseHeaders(404, -1) }

        lateinit var server: HttpServer

        /**
         * Stuurt een `200` met de volledige [lengte] als `Content-Length`. Schrijft daarna
         * [schrijf]; geeft die minder dan [lengte] bytes, dan sluit de server de verbinding af
         * zonder de rest — een afgebroken overdracht.
         */
        fun bijlage(lengte: Long, schrijf: (java.io.OutputStream) -> Unit): (HttpExchange) -> Unit = { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/pdf")
            exchange.sendResponseHeaders(200, lengte)
            schrijf(exchange.responseBody)
        }
    }

    override fun start(): Map<String, String> {
        val stub = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        stub.executor = Executors.newCachedThreadPool()
        stub.createContext("/api/v1/berichten/") { exchange ->
            try {
                gedrag(exchange)
            } catch (_: IOException) {
                // De uitvraag sloot de verbinding; dat is in een aantal tests juist de bedoeling.
            } finally {
                runCatching { exchange.close() }
            }
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
    }
}
