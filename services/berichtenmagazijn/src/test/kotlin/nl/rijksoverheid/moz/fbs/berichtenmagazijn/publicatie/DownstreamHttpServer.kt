package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lichtgewicht embedded HTTP-server voor end-to-end-tests van [PublicatieStream].
 * Vervanger voor WireMock: geen externe dep nodig, JDK-built-in.
 *
 * Per request slaat de server het body op zodat een test kan asserten op CloudEvent-
 * payload. De [statusVoorVolgendeAanroep] callback laat tests gesimuleerde
 * 5xx-responses retourneren om het retry-pad te valideren.
 */
class DownstreamHttpServer(
    private val pad: String = "/events",
    /** Bepaalt het statuscode van het n-de request (1-indexed). Default: altijd 202. */
    private val statusVoorAanroep: (Int) -> Int = { _ -> 202 },
) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val ontvangenBodies = ConcurrentLinkedQueue<String>()
    private val ontvangenHeaders = ConcurrentLinkedQueue<Map<String, List<String>>>()
    private val aanroepTeller = AtomicInteger(0)

    val poort: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$poort$pad"
    val bodies: List<String> get() = ontvangenBodies.toList()
    val headers: List<Map<String, List<String>>> get() = ontvangenHeaders.toList()
    val aantalAanroepen: Int get() = aanroepTeller.get()

    fun start() {
        server.createContext(pad) { exchange: HttpExchange ->
            val poging = aanroepTeller.incrementAndGet()
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            ontvangenBodies.add(body)
            ontvangenHeaders.add(exchange.requestHeaders.toMap())
            val status = statusVoorAanroep(poging)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
    }

    /** Reset call-counter, bodies en headers tussen tests die de server hergebruiken. */
    fun reset() {
        ontvangenBodies.clear()
        ontvangenHeaders.clear()
        aanroepTeller.set(0)
    }

    override fun close() {
        server.stop(0)
    }
}
